package madrileno.auth.routers

import madrileno.auth.domain.UserAgent
import madrileno.auth.routers.dto.{AuthWithFirebaseRequest, AuthenticatedResponse}
import madrileno.auth.services.{AuthenticationResult, AuthenticationService}
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuthRouter(authenticationService: AuthenticationService)(using TelemetryContext) extends BaseRouter {
  val routes: Route = {
    (post & path("auth" / "firebase") & entity(as[AuthWithFirebaseRequest]) & pathEndOrSingleSlash & optionalHeaderValueByName(
      "User-Agent"
    ) & extractClientIP) {
      (
        request,
        userAgent,
        ipAddress
      ) =>
        complete {
          authenticationService
            .authenticateWithFirebase(
              request.firebaseJwtToken,
              UserAgent(userAgent.getOrElse("Unknown")),
              ipAddress.getOrElse(throw new IllegalArgumentException("Could not establish request's IP address"))
            )
            .map[ToResponseMarshallable] {
              case AuthenticationResult.Authenticated(jwt, rt) => Ok      -> AuthenticatedResponse(jwt, rt.id)
              case AuthenticationResult.UserCreated(jwt, rt)   => Created -> AuthenticatedResponse(jwt, rt.id)
              case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
              case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "Invalid Firebase token")
            }
        }
    }
  }
}
