package madrileno.auth.routers

import madrileno.auth.domain.{FirebaseJwt, Provider}
import madrileno.auth.routers.dto.AuthWithFirebaseRequest
import madrileno.auth.services.{AuthVerifiers, DevAuthVerifier}
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

class AuthProviderUnavailableRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  override protected def testAuthVerifiers: AuthVerifiers = AuthVerifiers(Map(Provider.Dev -> DevAuthVerifier))

  path("/v1/auth/firebase")(
    supports(
      POST,
      description = "Authenticate with Firebase JWT token",
      summary = "Exchange Firebase token for internal JWT and refresh token",
      tags = Seq("Auth")
    )(
      onRequest(body = AuthWithFirebaseRequest(FirebaseJwt("test-token")))
        .respondsWith[Error[Unit]](ServiceUnavailable, description = "Firebase is not configured on this deployment")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Firebase authentication is not configured")
        }
    )
  )
}
