package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.{AuthContext, FirebaseJwt, UserAgent}
import madrileno.auth.repositories.RefreshTokenRepository
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthWithRefreshTokenRequest, AuthenticatedResponse, RefreshTokenDto}
import madrileno.auth.services.JwtService
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
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
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1")
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated existing user")
        .assert { ctx =>
          // Ensure user exists before testing the "existing user" path
          val setupRequest = Request[IO](Method.POST, Uri.unsafeFromString("/v1/auth/firebase"))
            .withEntity(AuthWithFirebaseRequest(FirebaseJwt("test-token")))
            .putHeaders("X-Forwarded-For" -> "127.0.0.1")
          val _ = allRoutes.orNotFound.run(setupRequest).unsafeRunSync()

          val response = ctx.performRequest(allRoutes)
          response.body.jwt.toString should not be empty
          response.body.refreshToken.toString should not be empty
        },
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")), headers = "127.0.0.1")
        .respondsWith[Error[Unit]](Locked, description = "User is blocked")
        .assert { ctx =>
          // Ensure user exists via direct firebase call
          val firebaseRequest = Request[IO](Method.POST, Uri.unsafeFromString("/v1/auth/firebase"))
            .withEntity(AuthWithFirebaseRequest(FirebaseJwt("test-token")))
            .putHeaders("X-Forwarded-For" -> "127.0.0.1")
          val firebaseResponse = allRoutes.orNotFound.run(firebaseRequest).unsafeRunSync()
          val authResponse     = firebaseResponse.as[AuthenticatedResponse].unsafeRunSync()

          // Decode JWT to get userId, then block the user
          val userId = jwtService.decode[AuthContext](authResponse.jwt.toString) match {
            case JwtService.DecodingResult.Decoded(ctx) => ctx.userId
            case other                                  => fail(s"Failed to decode JWT: $other")
          }
          application.transactor
            .inTransaction {
              application.userRepository.update(userId, _.copy(blockedAt = Some(Instant.now())), Instant.now())
            }
            .unsafeRunSync()

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
      onRequest(body = AuthWithRefreshTokenRequest(TestData.knownRefreshTokenId), headers = "127.0.0.1")
        .respondsWith[AuthenticatedResponse](Ok, description = "Authenticated with refresh token")
        .assert { ctx =>
          val user         = TestData.user()
          val refreshToken = TestData.refreshToken(id = TestData.knownRefreshTokenId, userId = user.id)
          val _ = application.transactor
            .inTransaction {
              application.userRepository.create(user, Instant.now()) *>
                new RefreshTokenRepository().save(refreshToken)
            }
            .unsafeRunSync()

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
