package madrileno.auth.routers

import com.comcast.ip4s.*
import madrileno.auth.domain.{AuthContext, ExternalAuthToken, Provider, RefreshTokenFamilyId, UserAgent}
import madrileno.auth.routers.dto.*
import madrileno.auth.services.{AuthenticationResult as AuthOutcome, *}
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import org.http4s.Header
import org.typelevel.ci.*
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import scala.concurrent.duration.*

class AuthRouter(authenticationService: AuthenticationService, override protected val rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  private val unknownIpAddress: IpAddress = ipv4"0.0.0.0"

  private val noStore = respondWithHeaders(Header.Raw(ci"Cache-Control", "no-store"), Header.Raw(ci"Pragma", "no-cache"))

  private def tokenResponse(invalidToken: String, providerUnavailable: => ToResponseMarshallable): AuthOutcome => ToResponseMarshallable = {
    case AuthOutcome.Authenticated(jwt, rt) => Ok -> AuthenticatedResponse(jwt, rt.secret, userCreated = false)
    case AuthOutcome.UserCreated(jwt, rt)   => Ok -> AuthenticatedResponse(jwt, rt.secret, userCreated = true)
    case AuthOutcome.UserBlocked            => error(Locked, "user-blocked", "User is blocked")
    case AuthOutcome.InvalidToken           => error(Unauthorized, "invalid-token", invalidToken)
    case AuthOutcome.ProviderUnavailable    => providerUnavailable
  }

  val routes: Route = noStore {
    (post & path("auth" / "firebase") & rateLimited("auth.firebase", to = 10, within = 1.minute) & entity(
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
            .map(
              tokenResponse(
                invalidToken = "Invalid Firebase token",
                providerUnavailable = error(ServiceUnavailable, "provider-unavailable", "Firebase authentication is not configured")
              )
            )
        }
    } ~
      (post & path("auth" / "refresh-token") & rateLimited("auth.refresh", to = 30, within = 1.minute) & entity(
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
              .map(
                tokenResponse(
                  invalidToken = "Invalid refresh token",
                  providerUnavailable = error(ServiceUnavailable, "provider-unavailable", "Authentication is not available")
                )
              )
          }
      } ~
      (post & path("auth" / "oidc" / Segment.as[Provider]) & rateLimited("auth.oidc", to = 10, within = 1.minute) & entity(
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
              .map(
                tokenResponse(
                  invalidToken = "Invalid ID token",
                  providerUnavailable = error(NotFound, "unknown-provider", s"No auth provider '$provider'")
                )
              )
          }
      } ~
      (post & path("auth" / "dev") & rateLimited("auth.dev", to = 10, within = 1.minute) & entity(
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
              .map(
                tokenResponse(
                  invalidToken = "dev auth requires an email address",
                  providerUnavailable = error(NotFound, "unknown-provider", "dev auth is not enabled")
                )
              )
          }
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    (get & path("auth" / "sessions") & pathEndOrSingleSlash) {
      complete {
        val command = ListSessionsCommand(authContext.userId)
        authenticationService
          .listSessions(command)
          .map[ToResponseMarshallable] { tokens => Ok -> tokens.map(SessionDto(_)) }
      }
    } ~
      (delete & path("auth" / "sessions" / JavaUUID.as[RefreshTokenFamilyId]) & pathEndOrSingleSlash) { familyId =>
        complete {
          val command = RevokeSessionCommand(authContext.userId, familyId)
          authenticationService
            .revokeSession(command)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      } ~ (delete & path("auth" / "sessions") & parameters("user-agent".as[UserAgent]) & pathEndOrSingleSlash) { userAgent =>
        complete {
          val command = RevokeSessionsByUserAgentCommand(authContext.userId, userAgent)
          authenticationService
            .revokeSessionsByUserAgent(command)
            .map[ToResponseMarshallable] { _ => NoContent }
        }
      }
  }
}
