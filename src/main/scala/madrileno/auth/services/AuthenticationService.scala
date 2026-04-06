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

import java.time.Duration

class AuthenticationService(
  userAuthRepository: UserAuthRepository,
  refreshTokenRepository: RefreshTokenRepository,
  userRepository: UserRepository,
  firebaseService: ExternalAuthVerifier,
  jwtService: JwtService,
  transactor: Transactor,
  mailer: Mailer
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {
  def authenticateWithFirebase(command: AuthenticateWithFirebaseCommand): IO[AuthenticationResult] = {
    firebaseService
      .verifyToken(command.firebaseJwt)
      .flatMap {
        case Left(t) =>
          logger
            .error(s"Failed to authenticate with Firebase: ${t.getMessage} ${t.getStackTrace.toList.mkString("\n")}")
            .as(AuthenticationResult.InvalidToken)
        case Right(verifiedToken) =>
          transactor.inTransaction {
            userAuthRepository
              .findForUpdate(verifiedToken.provider, verifiedToken.providerUserId)
              .flatMap {
                case Some(userAuth) =>
                  val userUpdater: User => User = _.withUpdatedProfile(verifiedToken.profile)
                  userAuthRepository.updateMetadata(userAuth.id, verifiedToken.metadata) *>
                    userRepository.update(userAuth.userId, userUpdater) *>
                    generateTokens(userAuth.userId, command.userAgent, command.ipAddress, AuthenticationResult.Authenticated.apply)
                case _ =>
                  for {
                    user <- IdGenerator.generateId(UserId).map(id => User(id, verifiedToken))
                    userAuth <-
                      IdGenerator
                        .generateId(UserAuthId)
                        .map(id => UserAuth(id, user.id, verifiedToken))
                    _ <- userRepository.create(user)
                    _ <- userAuthRepository.save(userAuth)
                    _ <- logger.info(s"Created new user: $user with Firebase UID: ${verifiedToken.providerUserId}")
                    _ <- user.emailAddress.fold(IO.unit) { email =>
                           mailer
                             .sendTransactionally(to = List(email.toString), template = WelcomeEmailTemplate(user.fullName), lang = Language.En)
                             .void
                         }
                    tokens <- generateTokens(user.id, command.userAgent, command.ipAddress, AuthenticationResult.UserCreated.apply)
                  } yield {
                    tokens
                  }
              }
          }
      }
  }

  def authenticateWithRefreshToken(command: AuthenticateWithRefreshTokenCommand): IO[AuthenticationResult] = {
    transactor.inTransaction {
      Clock[IO].realTimeInstant.flatMap { now =>
        refreshTokenRepository
          .findForUpdate(command.refreshToken)
          .flatMap {
            case Some(refreshToken) if refreshToken.isValid =>
              refreshTokenRepository.update(refreshToken.id, _.usedAt(now)) *>
                generateTokens(refreshToken.userId, command.userAgent, command.ipAddress, AuthenticationResult.Authenticated.apply)
            case Some(refreshToken) =>
              logger.warn(s"Refresh token $refreshToken is already used or deleted").as(AuthenticationResult.InvalidToken)
            case None =>
              logger.warn(s"Refresh token ${command.refreshToken} not found").as(AuthenticationResult.InvalidToken)
          }
      }
    }
  }

  def listRefreshTokens(command: ListRefreshTokensCommand): IO[List[RefreshToken]] = {
    transactor.inSession {
      refreshTokenRepository.listActive(command.userId)
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
          case Some(refreshToken) if !refreshToken.isValid =>
            logger.warn(s"Refresh token ${command.refreshTokenId} for user ${command.userId} is already deleted or used").as(None)
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
          .listActiveForUpdate(command.userId, command.userAgent)
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
          refreshTokenRepository.deleteUsedOrDeletedBefore(cutoff)
        }
      }
    }

  private def generateTokens(
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    success: (InternalJwt, RefreshToken) => AuthenticationResult
  ): DB[AuthenticationResult] = {
    (for {
      user <- userRepository
                .get(userId)
                .ensure(_.isActive, AuthenticationResult.UserBlocked)
      now <- Clock[IO].realTimeInstant.seal
      jwt = jwtService.encode(AuthContext(user), now)
      refreshToken <- IdGenerator.generateId(RefreshTokenId).map(id => RefreshToken.mint(id, now, user.id, userAgent, ipAddress)).seal
      _            <- refreshTokenRepository.save(refreshToken).seal
      _            <- logger.debug(s"Generated JWT: $jwt and RefreshToken: $refreshToken for user: $userId").seal
    } yield {
      success(jwt, refreshToken)
    }).run
  }
}

case class AuthenticateWithFirebaseCommand(
  firebaseJwt: FirebaseJwt,
  userAgent: UserAgent,
  ipAddress: IpAddress)

case class AuthenticateWithRefreshTokenCommand(
  refreshToken: RefreshTokenId,
  userAgent: UserAgent,
  ipAddress: IpAddress)

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserCreated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserBlocked
  case InvalidToken
}

case class ListRefreshTokensCommand(userId: UserId)

case class RevokeRefreshTokenCommand(userId: UserId, refreshTokenId: RefreshTokenId)

case class RevokeRefreshTokensCommand(userId: UserId, userAgent: UserAgent)
