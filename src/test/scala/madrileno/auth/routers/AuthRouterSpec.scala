package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.{AuthContext, FirebaseJwt, RefreshTokenId, UserAgent, UserAuth, UserAuthId}
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

  override def route: Route = application.routes(wsb)

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

  private def seedRefreshToken(): RefreshTokenId = {
    val user         = TestData.user()
    val refreshToken = TestData.refreshToken(userId = user.id)
    val _ = application.transactor
      .inTransaction {
        application.userRepository.create(user, Instant.now()) *>
          new RefreshTokenRepository().save(refreshToken)
      }
      .unsafeRunSync()
    refreshToken.id
  }

  path("/v1/auth/firebase")(
    supports(
      POST,
      description = "Authenticate with Firebase JWT token",
      summary = "Exchange Firebase token for internal JWT and refresh token",
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; a new user account was created (userCreated = true)")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
          response.body.userCreated shouldBe true
        },
      withSetup {
        seedFirebaseUser()
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token"))))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated; existing user (userCreated = false)")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
          response.body.userCreated shouldBe false
        },
      withSetup {
        seedFirebaseUser(blockedAt = Some(Instant.now()))
      }.request(_ => onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token"))))
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
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("invalid-token")))
        .respondsWith[Error[Unit]](Unauthorized, description = "Invalid Firebase token")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Invalid Firebase token")
        }
    )
  )

  path("/v1/auth/refresh-token")(
    supports(
      POST,
      description = "Authenticate with a refresh token",
      summary = "Exchange refresh token for a new JWT and refresh token",
      tags = Seq("Auth")
    )(
      withSetup(seedRefreshToken())
        .request(tokenId => onRequest(body = AuthWithRefreshTokenRequest(tokenId)))
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated with refresh token")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
          response.body.userCreated shouldBe false
        },
      onRequest(body = AuthWithRefreshTokenRequest(TestData.randomRefreshTokenId()))
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
      description =
        "List active sessions. Each entry's `createdAt` is when that refresh token was issued — login time, or the timestamp of the last JWT refresh that rotated it (refresh tokens are single-use, so the live one is always the newest in its chain).",
      summary = "Returns active refresh tokens for the authenticated user",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Auth")
    )(
      withSetup {
        val user  = TestData.user()
        val token = TestData.refreshToken(userId = user.id, userAgent = UserAgent("Firefox/142"), createdAt = Instant.parse("2026-05-01T10:00:00Z"))
        val _ = application.transactor
          .inTransaction(application.userRepository.create(user, Instant.now()) *> new RefreshTokenRepository().save(token))
          .unsafeRunSync()
        (user, token)
      }.request { case (user, _) => onRequest(security = bearer.apply(validJwt(AuthContext(user)))) }
        .respondsWith[List[RefreshTokenDto]](Ok, description = "Active (unused, unrevoked) refresh tokens for the authenticated user")
        .assert { case (ctx, (_, token)) =>
          val response = ctx.performRequest(allRoutes)
          response.body.map(_.id) shouldBe List(token.id)
          response.body.map(_.userAgent) shouldBe List(UserAgent("Firefox/142"))
          response.body.map(_.createdAt) shouldBe List(Instant.parse("2026-05-01T10:00:00Z"))
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
