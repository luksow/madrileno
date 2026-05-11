package madrileno.auth.routers

import com.comcast.ip4s.*
import madrileno.auth.domain.{AuthContext, RefreshTokenId, UserAgent}
import madrileno.auth.routers.dto.*
import madrileno.auth.services.*
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuthRouter(authenticationService: AuthenticationService)(using TelemetryContext) extends BaseRouter {
  private val unknownIpAddress: IpAddress = ipv4"0.0.0.0"

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
          val command =
            AuthenticateWithFirebaseCommand(request.firebaseJwtToken, UserAgent(userAgent.getOrElse("Unknown")), ipAddress.getOrElse(unknownIpAddress))
          authenticationService
            .authenticateWithFirebase(command)
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
            val command =
              AuthenticateWithRefreshTokenCommand(request.refreshToken, UserAgent(userAgent.getOrElse("Unknown")), ipAddress.getOrElse(unknownIpAddress))
            authenticationService
              .authenticateWithRefreshToken(command)
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
        val command = ListRefreshTokensCommand(authContext.userId)
        authenticationService
          .listRefreshTokens(command)
          .map[ToResponseMarshallable] { rts => Ok -> rts.map(RefreshTokenDto(_)) }
      }
    } ~
      (delete & path("auth" / "sessions" / JavaUUID.as[RefreshTokenId]) & pathEndOrSingleSlash) { refreshTokenId =>
        complete {
          val command = RevokeRefreshTokenCommand(authContext.userId, refreshTokenId)
          authenticationService
            .revokeRefreshToken(command)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      } ~ (delete & path("auth" / "sessions") & parameters("user-agent".as[UserAgent]) & pathEndOrSingleSlash) { userAgent =>
        complete {
          val command = RevokeRefreshTokensCommand(authContext.userId, userAgent)
          authenticationService
            .revokeRefreshTokens(command)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      }
  }
}
