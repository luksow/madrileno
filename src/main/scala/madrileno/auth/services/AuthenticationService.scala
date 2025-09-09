package madrileno.auth.services

import cats.effect.{Clock, IO}
import cats.effect.std.UUIDGen
import com.comcast.ip4s.IpAddress
import com.google.firebase.auth.FirebaseToken
import io.circe.parser
import madrileno.auth.domain.*
import madrileno.auth.repositories.*
import madrileno.user.domain.*
import madrileno.user.repositories.{UserRow, UserRowRepository}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import pl.iterators.sealedmonad.syntax.*
import skunk.Session

import java.net.URI

class AuthenticationService(
  userAuthRowRepository: UserAuthRowRepository,
  refreshTokenRowRepository: RefreshTokenRowRepository,
  userRowRepository: UserRowRepository,
  firebaseService: FirebaseService,
  jwtService: JwtService,
  transactor: Transactor
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {
  def authenticateWithFirebase(
    firebaseJwt: FirebaseJwt,
    userAgent: UserAgent,
    ipAddress: IpAddress
  ): IO[AuthenticationResult] = {
    firebaseService
      .verifyToken(firebaseJwt)
      .flatMap { firebaseToken =>
        transactor.inTransaction { case (session, _) =>
          userAuthRowRepository
            .findOneByFilter(
              UserAuthRowFilter(
                provider = p.equal(Provider.Firebase),
                providerUserId = p.equal(ProviderUserId(firebaseToken.getUid)),
                deletedAt = p.isNull
              ),
              lock = Lock.ForUpdate
            )(session)
            .map(_.map(_.toUserAuth))
            .flatMap {
              case Some(userAuth) =>
                updateUserAuthMetadata(userAuth, firebaseJwt)(session) *>
                  updateUser(userAuth.userId, firebaseToken)(session) *>
                  generateTokens(userAuth.userId, userAgent, ipAddress, AuthenticationResult.Authenticated.apply)(session)
              case _ =>
                createUser(firebaseJwt, firebaseToken)(session)
                  .flatMap(u => generateTokens(u.id, userAgent, ipAddress, AuthenticationResult.UserCreated.apply)(session))
            }
        }
      }
      .recoverWith { t =>
        logger
          .error(s"Failed to authenticate with Firebase: ${t.getMessage} ${t.getStackTrace.toList.mkString("\n")}")
          .as(AuthenticationResult.InvalidToken)
      }
  }

  def authenticateWithRefreshToken(
    refreshToken: RefreshTokenId,
    userAgent: UserAgent,
    ipAddress: IpAddress
  ): IO[AuthenticationResult] = {
    transactor.inTransaction { case (session, _) =>
      Clock[IO].realTimeInstant.flatMap { now =>
        refreshTokenRowRepository
          .findOneByFilter(RefreshTokenRowFilter(id = p.equal(refreshToken)), Lock.ForUpdate)(session)
          .map(_.map(_.toRefreshToken))
          .flatMap {
            case Some(refreshToken) if refreshToken.isValid =>
              refreshTokenRowRepository.update(RefreshTokenRow(refreshToken.usedAt(now)))(session) *>
                generateTokens(refreshToken.userId, userAgent, ipAddress, AuthenticationResult.Authenticated.apply)(session)
            case Some(refreshToken) =>
              logger.warn(s"Refresh token $refreshToken is already used or deleted").as(AuthenticationResult.InvalidToken)
            case None =>
              logger.warn(s"Refresh token $refreshToken not found").as(AuthenticationResult.InvalidToken)
          }
      }
    }
  }

  def listRefreshTokens(userId: UserId): IO[List[RefreshToken]] = {
    transactor.inSession { session =>
      refreshTokenRowRepository.findByFilter(RefreshTokenRowFilter(userId = p.equal(userId)))(session).map(_.map(_.toRefreshToken).filter(_.isValid))
    }
  }

  def revokeRefreshToken(userId: UserId, refreshTokenId: RefreshTokenId): IO[Option[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction { case (session, _) =>
        refreshTokenRowRepository
          .findOneByFilter(RefreshTokenRowFilter(id = p.equal(refreshTokenId), userId = p.equal(userId)), lock = Lock.ForUpdate)(session)
          .map(_.map(_.toRefreshToken))
          .flatMap {
            case Some(refreshToken) if refreshToken.isValid =>
              val updatedToken = refreshToken.deletedAt(now)
              refreshTokenRowRepository.update(RefreshTokenRow(updatedToken))(session) *>
                logger.info(s"Revoked refresh token $refreshTokenId for user $userId").as(Some(updatedToken))
            case Some(refreshToken) =>
              logger.warn(s"Refresh token $refreshTokenId for user $userId is already deleted or used").as(None)
            case _ =>
              logger.warn(s"Refresh token $refreshTokenId for user $userId not found").as(None)
          }
      }
    }
  }

  def revokeRefreshTokens(userId: UserId, userAgent: UserAgent): IO[List[RefreshToken]] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      transactor.inTransaction { case (session, _) =>
        refreshTokenRowRepository
          .findByFilter(RefreshTokenRowFilter(userId = p.equal(userId), userAgent = p.equal(userAgent), deletedAt = p.isNull), lock = Lock.ForUpdate)(
            session
          )
          .map(_.map(_.toRefreshToken))
          .flatMap { tokens =>
            val updatedTokens = tokens.filter(_.isValid).map(_.deletedAt(now))
            updatedTokens.map(RefreshTokenRow(_)).map(refreshTokenRowRepository.update(_)(session)).sequence.flatMap { updateResults =>
              if (updateResults.isEmpty)
                logger.warn(s"Refresh token for $userId and $userAgent were not found").as(updatedTokens)
              else
                logger.info(s"Revoked ${updatedTokens.size} refresh tokens for user $userId and user agent $userAgent").as(updatedTokens)
            }
          }
      }
    }
  }

  private def generateTokens(
    userId: UserId,
    userAgent: UserAgent,
    ipAddress: IpAddress,
    success: (InternalJwt, RefreshToken) => AuthenticationResult
  )(
    session: Session[IO]
  ): IO[AuthenticationResult] = {
    (for {
      user <- userRowRepository
                .getById(userId)(session)
                .map(_.toUser)
                .ensure(_.blockedAt.isEmpty, AuthenticationResult.UserBlocked)
      jwt = InternalJwt(jwtService.encode(AuthContext.toJson(AuthContext(user))))
      refreshToken <- IdGenerator.generateId(RefreshTokenId).map(id => RefreshToken(id, userId, userAgent, ipAddress)).seal
      refreshTokenRow = RefreshTokenRow(refreshToken)
      _ <- refreshTokenRowRepository.create(refreshTokenRow)(session).seal
      _ <- logger.debug(s"Generated JWT: $jwt and RefreshToken: $refreshToken for user: $userId").seal
    } yield {
      success(jwt, refreshToken)
    }).run
  }

  private def firebaseTokenToMetadata(firebaseJwt: FirebaseJwt): Metadata = {
    val claimsPart   = firebaseJwt.split("\\.")(1)
    val claimsInJson = new String(java.util.Base64.getUrlDecoder.decode(claimsPart))
    val claims       = parser.parse(claimsInJson).getOrElse(throw new IllegalStateException("Invalid Firebase JWT claims"))
    Metadata(claims)
  }

  private def updateUserAuthMetadata(userAuth: UserAuth, firebaseJwt: FirebaseJwt)(session: Session[IO]): IO[Unit] = {
    val metadata = firebaseTokenToMetadata(firebaseJwt)
    userAuthRowRepository.updateById(userAuth.id, _.copy(metadata = metadata))(session)
  }

  private def updateUser(userId: UserId, firebaseToken: FirebaseToken)(session: Session[IO]): IO[Unit] = {
    userRowRepository.updateById(
      userId,
      _.copy(
        fullName = Option(firebaseToken.getName).map(FullName.apply),
        emailAddress = Option(firebaseToken.getEmail).map(EmailAddress.apply),
        emailVerified = firebaseToken.isEmailVerified,
        avatarUrl = Option(firebaseToken.getPicture).map(URI.create)
      )
    )(session)
  }

  private def createUser(firebaseJwt: FirebaseJwt, firebaseToken: FirebaseToken)(session: Session[IO]): IO[User] = {
    for {
      user <- IdGenerator.generateId(UserId).map(id => firebaseTokenToUser(id, firebaseToken))
      userAuth <-
        IdGenerator
          .generateId(UserAuthId)
          .map(id => firebaseTokenToUserAuth(id, user.id, firebaseJwt, firebaseToken))
      userRow     = UserRow(user)
      userAuthRow = UserAuthRow(userAuth)
      _ <- userRowRepository.create(userRow)(session)
      _ <- userAuthRowRepository.create(userAuthRow)(session)
      _ <- logger.info(s"Created new user: $user with Firebase token: ${firebaseToken.getUid}")
    } yield {
      user
    }
  }

  private def firebaseTokenToUserAuth(
    id: UserAuthId,
    userId: UserId,
    firebaseJwt: FirebaseJwt,
    firebaseToken: FirebaseToken
  ): UserAuth = {
    val metadata = firebaseTokenToMetadata(firebaseJwt)
    UserAuth(id, userId, Provider.Firebase, ProviderUserId(firebaseToken.getUid), Credential(firebaseToken.getUid), metadata)
  }

  private def firebaseTokenToUser(id: UserId, firebaseToken: FirebaseToken): User = {
    User(
      id,
      Option(firebaseToken.getName).map(FullName.apply),
      Option(firebaseToken.getEmail).map(EmailAddress.apply),
      firebaseToken.isEmailVerified,
      Option(firebaseToken.getPicture).map(URI.create),
      blockedAt = None
    )
  }
}

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserCreated(jwt: InternalJwt, refreshToken: RefreshToken)
  case UserBlocked
  case InvalidToken
}
