package madrileno.auth.routers

import madrileno.auth.domain.FirebaseJwt
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthWithRefreshTokenRequest, RefreshTokenDto}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.EmptyBody
import pl.iterators.stir.server.Route

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
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("invalid-token")), headers = "127.0.0.1")
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
      headers = h[String]("X-Forwarded-For", "Client IP address"),
      tags = Seq("Auth")
    )(
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
      queryParameters = q[String]("user-agent"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), queryParameters = "test-agent")
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
      pathParameters = p[String]("sessionId"),
      tags = Seq("Auth")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())), pathParameters = java.util.UUID.randomUUID().toString)
        .respondsWith[EmptyBody](NoContent, description = "Session revoked")
        .assert { ctx =>
          ctx.performRequest(allRoutes)
        }
    )
  )
}
