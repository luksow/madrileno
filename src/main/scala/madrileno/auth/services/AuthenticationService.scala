package madrileno.auth.services

import cats.effect.std.{SecureRandom, UUIDGen}
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.auth.emails.WelcomeEmailTemplate
import madrileno.auth.repositories.*
import madrileno.user.domain.*
import madrileno.user.repositories.*
import madrileno.utils.crypto.{IdGenerator, RandomSecret}
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
  Clock[IO],
  SecureRandom[IO])
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
      generateTokensInNewFamily(userAuth.userId, command.userAgent, command.ipAddress, now, AuthenticationResult.Authenticated.apply)
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
      _        <- user.emailAddress.fold(IO.unit) { email =>
             mailer.sendTransactionally(to = List(email.toString), template = WelcomeEmailTemplate(user.fullName), lang = Language.En).void
           }
      tokens <- generateTokensInNewFamily(user.id, command.userAgent, command.ipAddress, now, AuthenticationResult.UserCreated.apply)
    } yield tokens
  }

  def authenticateWithRefreshToken(command: AuthenticateWithRefreshTokenCommand): IO[AuthenticationResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        refreshTokenRepository
          .findAndLockFamilyBySecretHash(command.refreshToken.hash)
          .flatMap {
            case Some(refreshToken) if refreshToken.isValid(now) =>
              refreshTokenRepository.update(refreshToken.id, _.usedAt(now)) *>
                generateTokens(
                  refreshToken.userId,
                  refreshToken.familyId,
                  command.userAgent,
                  command.ipAddress,
                  now,
                  AuthenticationResult.Authenticated.apply
                )
            case Some(refreshToken) if refreshToken.isUsed && !refreshToken.isRevoked && refreshToken.wasUsedWithin(config.reuseGrace, now) =>
              logger
                .warn(s"Refresh token ${refreshToken.id} was replayed within the reuse grace window; family ${refreshToken.familyId} kept")
                .as(AuthenticationResult.InvalidToken)
            case Some(refreshToken) if refreshToken.isUsed && !refreshToken.isRevoked =>
              refreshTokenRepository.revokeFamily(refreshToken.familyId, now) *>
                logger
                  .warn(s"Refresh token ${refreshToken.id} was replayed; revoked family ${refreshToken.familyId} for user ${refreshToken.userId}")
                  .as(AuthenticationResult.InvalidToken)
            case Some(refreshToken) =>
              logger.warn(s"Refresh token ${refreshToken.id} is revoked or expired").as(AuthenticationResult.InvalidToken)
            case None =>
              logger.warn("Refresh token not found").as(AuthenticationResult.InvalidToken)
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

  private def generateTokensInNewFamily(
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    now: Instant,
    success: (InternalJwt, IssuedRefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    IdGenerator.generateId(RefreshTokenFamilyId).flatMap { familyId =>
      generateTokens(userId, familyId, userAgent, ipAddress, now, success)
    }
  }

  private def generateTokens(
    userId: UserId,
    familyId: RefreshTokenFamilyId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    now: Instant,
    success: (InternalJwt, IssuedRefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    (for {
      user <- userRepository
                .get(userId)
                .ensure(_.isActive, AuthenticationResult.UserBlocked)
      jwt = jwtService.encode(AuthContext(user), now)
      id     <- IdGenerator.generateId(RefreshTokenId).seal
      secret <- RandomSecret.generate(RefreshTokenSecret.byteLength).map(RefreshTokenSecret.apply).seal
      refreshToken = RefreshToken.mint(id, familyId, secret.hash, now, user.id, userAgent, ipAddress, config.validFor)
      _ <- refreshTokenRepository.save(refreshToken).seal
      _ <- logger.debug(s"Issued refresh token ${refreshToken.id} in family $familyId for user: $userId").seal
    } yield {
      success(jwt, IssuedRefreshToken(refreshToken, secret))
    }).run
  }
}

final case class AuthenticateWithExternalTokenCommand(
  token: ExternalAuthToken,
  userAgent: UserAgent,
  ipAddress: IpAddress)

final case class AuthenticateWithRefreshTokenCommand(
  refreshToken: RefreshTokenSecret,
  userAgent: UserAgent,
  ipAddress: IpAddress)

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt, refreshToken: IssuedRefreshToken)
  case UserCreated(jwt: InternalJwt, refreshToken: IssuedRefreshToken)
  case UserBlocked
  case InvalidToken
  case ProviderUnavailable
}

final case class ListRefreshTokensCommand(userId: UserId)

final case class RevokeRefreshTokenCommand(userId: UserId, refreshTokenId: RefreshTokenId)

final case class RevokeRefreshTokensCommand(userId: UserId, userAgent: UserAgent)

object AuthenticationService {
  final case class Config(validFor: Duration, reuseGrace: Duration) derives ConfigReader
}
