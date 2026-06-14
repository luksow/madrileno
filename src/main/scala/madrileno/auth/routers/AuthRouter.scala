package madrileno.auth.routers

import com.comcast.ip4s.*
import madrileno.auth.domain.{AuthContext, ExternalAuthToken, Provider, RefreshTokenId, UserAgent}
import madrileno.auth.routers.dto.*
import madrileno.auth.services.*
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiter, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import scala.concurrent.duration.*

class AuthRouter(authenticationService: AuthenticationService, rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {
  override protected val rateLimiter: RateLimiter = rateLimiterRuntime.rateLimiter

  private val unknownIpAddress: IpAddress = ipv4"0.0.0.0"

  val routes: Route = {
    (post & path("auth" / "firebase") & rateLimited("auth.firebase", to = 10, within = 1.minute, by = byClientIpForwarded) & entity(
      as[AuthWithFirebaseRequest]
    ) & pathEndOrSingleSlash & optionalHeaderValueByName("User-Agent") & extractClientIP) {
      (
        request,
        userAgent,
        ipAddress
      ) =>
        complete {
          val command =
            AuthenticateWithExternalTokenCommand(
              ExternalAuthToken(request.firebaseJwtToken),
              UserAgent(userAgent.getOrElse("Unknown")),
              ipAddress.getOrElse(unknownIpAddress)
            )
          authenticationService
            .authenticateWithProvider(Provider.Firebase, command)
            .map[ToResponseMarshallable] {
              case AuthenticationResult.Authenticated(jwt, rt) => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = false)
              case AuthenticationResult.UserCreated(jwt, rt)   => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = true)
              case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
              case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "Invalid Firebase token")
              case AuthenticationResult.ProviderUnavailable =>
                error(ServiceUnavailable, "provider-unavailable", "Firebase authentication is not configured")
            }
        }
    } ~
      (post & path("auth" / "refresh-token") & rateLimited("auth.refresh", to = 30, within = 1.minute, by = byClientIpForwarded) & entity(
        as[AuthWithRefreshTokenRequest]
      ) & pathEndOrSingleSlash & optionalHeaderValueByName("User-Agent") & extractClientIP) {
        (
          request,
          userAgent,
          ipAddress
        ) =>
          complete {
            val command =
              AuthenticateWithRefreshTokenCommand(
                request.refreshToken,
                UserAgent(userAgent.getOrElse("Unknown")),
                ipAddress.getOrElse(unknownIpAddress)
              )
            authenticationService
              .authenticateWithRefreshToken(command)
              .map[ToResponseMarshallable] {
                case AuthenticationResult.Authenticated(jwt, rt) => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = false)
                case AuthenticationResult.UserCreated(jwt, rt)   => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = true)
                case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
                case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "Invalid refresh token")
                case AuthenticationResult.ProviderUnavailable => error(ServiceUnavailable, "provider-unavailable", "Authentication is not available")
              }
          }
      } ~
      (post & path("auth" / "oidc" / Segment.as[Provider]) & rateLimited("auth.oidc", to = 10, within = 1.minute, by = byClientIpForwarded) & entity(
        as[AuthWithOidcRequest]
      ) & pathEndOrSingleSlash & optionalHeaderValueByName("User-Agent") & extractClientIP) {
        (
          provider,
          request,
          userAgent,
          ipAddress
        ) =>
          complete {
            val command =
              AuthenticateWithExternalTokenCommand(
                ExternalAuthToken(request.idToken),
                UserAgent(userAgent.getOrElse("Unknown")),
                ipAddress.getOrElse(unknownIpAddress)
              )
            authenticationService
              .authenticateWithProvider(provider, command)
              .map[ToResponseMarshallable] {
                case AuthenticationResult.Authenticated(jwt, rt) => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = false)
                case AuthenticationResult.UserCreated(jwt, rt)   => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = true)
                case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
                case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "Invalid ID token")
                case AuthenticationResult.ProviderUnavailable    => error(NotFound, "unknown-provider", s"No auth provider '$provider'")
              }
          }
      } ~
      (post & path("auth" / "dev") & rateLimited("auth.dev", to = 10, within = 1.minute, by = byClientIpForwarded) & entity(
        as[AuthWithEmailRequest]
      ) & pathEndOrSingleSlash & optionalHeaderValueByName("User-Agent") & extractClientIP) {
        (
          request,
          userAgent,
          ipAddress
        ) =>
          complete {
            val command =
              AuthenticateWithExternalTokenCommand(
                ExternalAuthToken(request.email),
                UserAgent(userAgent.getOrElse("Unknown")),
                ipAddress.getOrElse(unknownIpAddress)
              )
            authenticationService
              .authenticateWithProvider(Provider.Dev, command)
              .map[ToResponseMarshallable] {
                case AuthenticationResult.Authenticated(jwt, rt) => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = false)
                case AuthenticationResult.UserCreated(jwt, rt)   => Ok -> AuthenticatedResponse(jwt, rt.id, userCreated = true)
                case AuthenticationResult.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
                case AuthenticationResult.InvalidToken           => error(Unauthorized, "invalid-token", "dev auth requires an email address")
                case AuthenticationResult.ProviderUnavailable    => error(NotFound, "unknown-provider", "dev auth is not enabled")
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
