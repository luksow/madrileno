package madrileno.auth.routers

import madrileno.auth.domain.{AuthContext, RefreshTokenId, UserAgent}
import madrileno.auth.routers.dto.*
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
    } ~
      (post & path("auth" / "refresh-token") & entity(as[AuthWithRefreshTokenRequest]) & pathEndOrSingleSlash & optionalHeaderValueByName(
        "User-Agent"
      ) & extractClientIP) {
        (
          request,
          userAgent,
          ipAddress
        ) =>
          complete {
            authenticationService
              .authenticateWithRefreshToken(
                request.refreshToken,
                UserAgent(userAgent.getOrElse("Unknown")),
                ipAddress.getOrElse(throw new IllegalArgumentException("Could not establish request's IP address"))
              )
              .map[ToResponseMarshallable] {
                case AuthenticationResult.Authenticated(jwt, rt) => Ok      -> AuthenticatedResponse(jwt, rt.id)
                case AuthenticationResult.UserCreated(jwt, rt)   => Created -> AuthenticatedResponse(jwt, rt.id)
                case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
                case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "Invalid refresh token")
              }
          }
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    (get & path("auth" / "sessions") & pathEndOrSingleSlash) {
      complete {
        authenticationService
          .listRefreshTokens(authContext.userId)
          .map[ToResponseMarshallable] { rts => Ok -> rts.map(RefreshTokenDto(_)) }
      }
    } ~
      (delete & path("auth" / "sessions" / JavaUUID.as[RefreshTokenId]) & pathEndOrSingleSlash) { refreshTokenId =>
        complete {
          authenticationService
            .revokeRefreshToken(authContext.userId, refreshTokenId)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      } ~ (delete & path("auth" / "sessions") & parameters("user-agent".as[UserAgent]) & pathEndOrSingleSlash) { userAgent =>
        complete {
          authenticationService
            .revokeRefreshTokens(authContext.userId, userAgent)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      }
  }
}
