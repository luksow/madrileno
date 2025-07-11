package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.AuthContext
import madrileno.auth.services.JwtService
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import org.http4s
import org.http4s.*
import pl.iterators.stir.server.directives.SecurityDirectives.AuthenticationResult
import pl.iterators.stir.server.directives.{AuthenticationResult, SecurityDirectives}

class UserAuthenticator(jwtService: JwtService)(using TelemetryContext)
    extends (Option[Credentials] => IO[AuthenticationResult[AuthContext]])
    with LoggingSupport {
  override def apply(credentialsOpt: Option[Credentials]): IO[AuthenticationResult[AuthContext]] = {
    credentialsOpt match {
      case Some(credentials: Credentials.Token) if credentials.authScheme == AuthScheme.Bearer =>
        jwtService.decode(credentials.token) match {
          case JwtService.DecodingResult.Decoded(json) =>
            AuthContext.from(json) match {
              case Right(authContext) => IO.pure(AuthenticationResult.success(authContext))
              case Left(error) =>
                logger.warn(s"Malformed credentials: $credentials, error: $error").as(AppChallenge)
            }
          case JwtService.DecodingResult.InvalidToken(t) =>
            logger.warn(t)(s"Invalid token: $credentials").as(AppChallenge)
          case JwtService.DecodingResult.ParsingFailure(t) =>
            logger.warn(t)(s"Token parsing failure: $credentials").as(AppChallenge)
          case JwtService.DecodingResult.Expired =>
            logger.warn(s"Expired token: $credentials").as(AppChallenge)
        }
      case _ => AppChallengeIO
    }
  }
  private val AppChallenge: SecurityDirectives.AuthenticationResult[Nothing] =
    AuthenticationResult.failWithChallenge(Challenge(scheme = "Bearer", realm = "madrileno"))

  private val AppChallengeIO: IO[SecurityDirectives.AuthenticationResult[Nothing]] =
    IO.pure(AppChallenge)
}
