package madrileno.auth.routers

import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthenticatedResponse}
import madrileno.auth.services.{AuthenticationResult, AuthenticationService}
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuthRouter(authenticationService: AuthenticationService)(using TelemetryContext) extends BaseRouter {
  val routes: Route = {
    (post & path("auth" / "firebase") & entity(as[AuthWithFirebaseRequest]) & pathEndOrSingleSlash) { request =>
      complete {
        authenticationService.authenticateWithFirebase(request.firebaseJwtToken).map[ToResponseMarshallable] {
          case AuthenticationResult.Authenticated(jwt) => Ok      -> AuthenticatedResponse(jwt)
          case AuthenticationResult.UserCreated(jwt)   => Created -> AuthenticatedResponse(jwt)
          case AuthenticationResult.InvalidToken       => error(Unauthorized, "invalid-token", "Invalid Firebase token")
        }
      }
    }
  }
}
