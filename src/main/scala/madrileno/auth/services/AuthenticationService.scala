package madrileno.auth.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.auth.emails.WelcomeEmailTemplate
import madrileno.auth.repositories.*
import madrileno.user.domain.*
import madrileno.user.repositories.*
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.*
import madrileno.utils.mailer.{Language, Mailer}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.{CronExpression, Schedule, Task}
import pl.iterators.sealedmonad.syntax.*
import pureconfig.*

import java.time.{Duration, Instant}

class AuthenticationService(
  userAuthRepository: UserAuthRepository,
  refreshTokenRepository: RefreshTokenRepository,
  userRepository: UserRepository,
  verifiers: AuthVerifiers,
  jwtService: JwtService,
  transactor: Transactor,
  mailer: Mailer,
  config: AuthenticationService.Config
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {
  def authenticateWithProvider(provider: Provider, command: AuthenticateWithExternalTokenCommand): IO[AuthenticationResult] = {
    verifiers.get(provider) match {
      case None =>
        logger.warn(s"No auth verifier configured for provider $provider").as(AuthenticationResult.ProviderUnavailable)
      case Some(verifier) =>
        verifier.verifyToken(command.token).flatMap {
          case Left(t) =>
            logger
              .error(s"Failed to authenticate with $provider: ${t.getMessage} ${t.getStackTrace.toList.mkString("\n")}")
              .as(AuthenticationResult.InvalidToken)
          case Right(verifiedToken) if verifiedToken.provider != provider =>
            logger
              .error(s"Verifier for $provider returned a token claiming provider ${verifiedToken.provider}; refusing")
              .as(AuthenticationResult.InvalidToken)
          case Right(verifiedToken) =>
            transactor.inTransaction(upsertAndIssueTokens(verifiedToken, command))
        }
    }
  }

  private def upsertAndIssueTokens(verifiedToken: VerifiedExternalToken, command: AuthenticateWithExternalTokenCommand)
    : DBInTransaction[AuthenticationResult] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      userAuthRepository.findForUpdate(verifiedToken.provider, verifiedToken.providerUserId).flatMap {
        case Some(userAuth) => updateExistingUser(userAuth, verifiedToken, command, now)
        case None           => createNewUser(verifiedToken, command, now)
      }
    }
  }

  private def updateExistingUser(
    userAuth: UserAuth,
    verifiedToken: VerifiedExternalToken,
    command: AuthenticateWithExternalTokenCommand,
    now: Instant
  ): DBInTransaction[AuthenticationResult] = {
    val userUpdater: User => User = _.withUpdatedProfile(verifiedToken.profile)
    userAuthRepository.updateMetadata(userAuth.id, verifiedToken.metadata) *>
      userRepository.update(userAuth.userId, userUpdater, now) *>
      generateTokens(userAuth.userId, command.userAgent, command.ipAddress, now, AuthenticationResult.Authenticated.apply)
  }

  private def createNewUser(
    verifiedToken: VerifiedExternalToken,
    command: AuthenticateWithExternalTokenCommand,
    now: Instant
  ): DBInTransaction[AuthenticationResult] = {
    for {
      user     <- IdGenerator.generateId(UserId).map(id => User(id, verifiedToken))
      userAuth <- IdGenerator.generateId(UserAuthId).map(id => UserAuth(id, user.id, verifiedToken))
      _        <- userRepository.create(user, now)
      _        <- userAuthRepository.save(userAuth, now)
      _        <- logger.info(s"Created new user: $user via ${verifiedToken.provider} (${verifiedToken.providerUserId})")
      _ <- user.emailAddress.fold(IO.unit) { email =>
             mailer.sendTransactionally(to = List(email.toString), template = WelcomeEmailTemplate(user.fullName), lang = Language.En).void
           }
      tokens <- generateTokens(user.id, command.userAgent, command.ipAddress, now, AuthenticationResult.UserCreated.apply)
    } yield tokens
  }

  def authenticateWithRefreshToken(command: AuthenticateWithRefreshTokenCommand): IO[AuthenticationResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        refreshTokenRepository
          .findForUpdate(command.refreshToken)
          .flatMap {
            case Some(refreshToken) if refreshToken.isValid(now) =>
              refreshTokenRepository.update(refreshToken.id, _.usedAt(now)) *>
                generateTokens(refreshToken.userId, command.userAgent, command.ipAddress, now, AuthenticationResult.Authenticated.apply)
            case Some(refreshToken) =>
              logger.warn(s"Refresh token $refreshToken is already used, deleted, or expired").as(AuthenticationResult.InvalidToken)
            case None =>
              logger.warn(s"Refresh token ${command.refreshToken} not found").as(AuthenticationResult.InvalidToken)
          }
      }
    }
  }

  def listRefreshTokens(command: ListRefreshTokensCommand): IO[List[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inSession {
        refreshTokenRepository.listActive(command.userId, now)
      }
    }
  }

  def revokeRefreshToken(command: RevokeRefreshTokenCommand): IO[Option[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction {
        refreshTokenRepository.findForUpdate(command.refreshTokenId).flatMap {
          case Some(refreshToken) if refreshToken.userId != command.userId =>
            logger
              .warn(
                s"Attempt to revoke refresh token ${command.refreshTokenId} for user ${command.userId} which belongs to another user ${refreshToken.userId}"
              )
              .as(None)
          case Some(refreshToken) if !refreshToken.isValid(now) =>
            logger.warn(s"Refresh token ${command.refreshTokenId} for user ${command.userId} is already deleted, used, or expired").as(None)
          case Some(refreshToken) =>
            val deleted = refreshToken.deletedAt(now)
            refreshTokenRepository.update(deleted) *>
              logger.info(s"Revoked refresh token ${command.refreshTokenId} for user ${command.userId}").as(Some(deleted))
          case _ =>
            logger.warn(s"Refresh token ${command.refreshTokenId} for user ${command.userId} not found").as(None)
        }
      }
    }
  }

  def revokeRefreshTokens(command: RevokeRefreshTokensCommand): IO[List[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction {
        refreshTokenRepository
          .listActiveForUpdate(command.userId, command.userAgent, now)
          .flatMap { tokens =>
            val updatedTokens = tokens.map(_.deletedAt(now))
            updatedTokens.map(refreshTokenRepository.update).sequence.flatMap { updateResults =>
              if (updateResults.isEmpty)
                logger.warn(s"Refresh token for ${command.userId} and ${command.userAgent} were not found").as(updatedTokens)
              else
                logger
                  .info(s"Revoked ${updatedTokens.size} refresh tokens for user ${command.userId} and user agent ${command.userAgent}")
                  .as(updatedTokens)
            }
          }
      }
    }
  }

  val cleanupExpiredRefreshTokensTask: Task[Unit] =
    Task.recurring("cleanup-expired-refresh-tokens", Schedule.Cron(CronExpression.unsafeParse("0 0 1 ? * 0-6"))) { _ =>
      Clock[IO].realTimeInstant.flatMap { now =>
        val cutoff = now.minus(Duration.ofDays(60))
        transactor.inSession {
          refreshTokenRepository.deleteStaleBefore(cutoff)
        }
      }
    }

  private def generateTokens(
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    now: Instant,
    success: (InternalJwt, RefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    (for {
      user <- userRepository
                .get(userId)
                .ensure(_.isActive, AuthenticationResult.UserBlocked)
      jwt = jwtService.encode(AuthContext(user), now)
      refreshToken <- IdGenerator
                        .generateId(RefreshTokenId)
                        .map(id => RefreshToken.mint(id, now, user.id, userAgent, ipAddress, config.validFor))
                        .seal
      _ <- refreshTokenRepository.save(refreshToken).seal
      _ <- logger.debug(s"Generated JWT: $jwt and RefreshToken: $refreshToken for user: $userId").seal
    } yield {
      success(jwt, refreshToken)
    }).run
  }
}

final case class AuthenticateWithExternalTokenCommand(
  token: ExternalAuthToken,
  userAgent: UserAgent,
  ipAddress: IpAddress)

final case class AuthenticateWithRefreshTokenCommand(
  refreshToken: RefreshTokenId,
  userAgent: UserAgent,
  ipAddress: IpAddress)

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserCreated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserBlocked
  case InvalidToken
  case ProviderUnavailable
}

final case class ListRefreshTokensCommand(userId: UserId)

final case class RevokeRefreshTokenCommand(userId: UserId, refreshTokenId: RefreshTokenId)

final case class RevokeRefreshTokensCommand(userId: UserId, userAgent: UserAgent)

object AuthenticationService {
  // `validFor` is `Option[Duration]` so the config can be omitted entirely to mean "no expiry"
  // (leaked tokens still revocable; current behavior preserved for projects that haven't opted in).
  // Set via `refresh-token.valid-for` HOCON or `REFRESH_TOKEN_VALID_FOR` env var.
  final case class Config(validFor: Option[Duration]) derives ConfigReader
}
