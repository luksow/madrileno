package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.{FirebaseJwt, RefreshTokenId, UserAgent, UserAuth, UserAuthId}
import madrileno.auth.repositories.{RefreshTokenRepository, UserAuthRepository}
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthWithRefreshTokenRequest, AuthenticatedResponse, RefreshTokenDto}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.{User, UserId}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

import java.time.Instant
import java.util.UUID

class AuthRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes

  path("/v1/auth/firebase")(
    supports(
      POST,
      description = "Authenticate with Firebase JWT token",
      summary = "Exchange Firebase token for internal JWT and refresh token",
      headers = h[String]("X-Forwarded-For", "Client IP address"),
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1")
        .respondsWith[AuthenticatedResponse](Created, description = "Created new user and authenticated")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
        },
      withSetup {
        seedFirebaseUser()
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1"))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated existing user")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
        },
      withSetup {
        seedFirebaseUser(blockedAt = Some(Instant.now()))
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1"))
        .respondsWith[Error[Unit]](Locked, description = "User is blocked")
        .assert { case (ctx, userId) =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("User is blocked")

          // Unblock the user so subsequent tests aren't poisoned
          application.transactor
            .inTransaction {
              application.userRepository.update(userId, _.copy(blockedAt = None), Instant.now())
            }
            .unsafeRunSync()
        },
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("invalid-token")), headers = "127.0.0.1")
        .respondsWith[Error[Unit]](Unauthorized, description = "Invalid Firebase token")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid Firebase token")
        }
    ),
    supports(POST, description = "Authenticate with Firebase JWT token without IP", summary = "Missing client IP returns 400", tags = Seq("Auth"))(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
        .respondsWith[Error[Unit]](BadRequest, description = "Missing client IP address")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Could not establish request's IP address")
        }
    )
  )

  path("/v1/auth/refresh-token")(
    supports(
      POST,
      description = "Authenticate with a refresh token",
      summary = "Exchange refresh token for a new JWT and refresh token",
      headers = h[String]("X-Forwarded-For", "Client IP address"),
      tags = Seq("Auth")
    )(
      withSetup {
        val user         = TestData.user()
        val refreshToken = TestData.refreshToken(userId = user.id)
        val _ = application.transactor
          .inTransaction {
            application.userRepository.create(user, Instant.now()) *>
              new RefreshTokenRepository().save(refreshToken)
          }
          .unsafeRunSync()
        refreshToken.id
      }.request { (tokenId: RefreshTokenId) =>
        onRequest(body = AuthWithRefreshTokenRequest(tokenId), headers = "127.0.0.1")
      }.respondsWith[AuthenticatedResponse](Ok, description = "Authenticated with refresh token")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
        },
      onRequest(body = AuthWithRefreshTokenRequest(TestData.randomRefreshTokenId()), headers = "127.0.0.1")
        .respondsWith[Error[Unit]](Unauthorized, description = "Invalid or expired refresh token")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid refresh token")
        }
    )
  )

  path("/v1/auth/sessions")(
    supports(
      GET,
      description = "List active sessions",
      summary = "Returns active refresh tokens for the authenticated user",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())))
        .respondsWith[List[RefreshTokenDto]](Ok, description = "List of active sessions")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    ),
    supports(
      DELETE,
      description = "Revoke sessions by user agent",
      summary = "Revoke all refresh tokens for a given user agent",
      securitySchemes = Seq(bearerScheme),
      queryParameters = q[UserAgent]("user-agent"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), queryParameters = UserAgent("test-agent"))
        .respondsWith[EmptyBody](NoContent, description = "Sessions revoked")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )

  private def seedFirebaseUser(blockedAt: Option[Instant] = None): UserId = {
    val userAuthRepository = new UserAuthRepository()
    val now                = Instant.now()
    application.transactor
      .inTransaction {
        userAuthRepository.findForUpdate(firebaseToken.provider, firebaseToken.providerUserId).flatMap {
          case Some(userAuth) =>
            blockedAt.fold(IO.pure(userAuth.userId)) { ts =>
              application.userRepository.update(userAuth.userId, _.copy(blockedAt = Some(ts)), ts).as(userAuth.userId)
            }
          case None =>
            val userId   = UserId(UUID.randomUUID())
            val user     = User(userId, firebaseToken).copy(blockedAt = blockedAt)
            val userAuth = UserAuth(UserAuthId(UUID.randomUUID()), userId, firebaseToken)
            application.userRepository.create(user, now) *>
              userAuthRepository.save(userAuth, now).as(userId)
        }
      }
      .unsafeRunSync()
  }

  path("/v1/auth/sessions/{sessionId}")(
    supports(
      DELETE,
      description = "Revoke a specific session",
      summary = "Revoke a refresh token by its ID",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[UUID]("sessionId"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), pathParameters = UUID.randomUUID())
        .respondsWith[EmptyBody](NoContent, description = "Session revoked")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )
}
