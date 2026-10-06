package madrileno.auth.routers

import cats.effect.IO
import madrileno.auth.domain.AuthContext
import madrileno.auth.services.{DecodingResult, JwtService}
import madrileno.utils.observability.{Fingerprint, LoggingSupport, TelemetryContext}
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
        jwtService.decode[AuthContext](credentials.token) match {
          case DecodingResult.Decoded(authContext) => IO.pure(Right(authContext))
          case DecodingResult.InvalidToken(t)      =>
            logger.warn(t)(s"Invalid token ${Fingerprint(credentials.token)}").as(AppChallenge)
          case DecodingResult.ParsingFailure(t) =>
            logger.warn(t)(s"Token parsing failure ${Fingerprint(credentials.token)}").as(AppChallenge)
          case DecodingResult.Expired(_) =>
            logger.warn(s"Expired token ${Fingerprint(credentials.token)}").as(AppChallenge)
        }
      case _ => AppChallengeIO
    }
  }
  private val AppChallenge: SecurityDirectives.AuthenticationResult[Nothing] =
    AuthenticationResult.failWithChallenge(Challenge(scheme = "Bearer", realm = "madrileno"))

  private val AppChallengeIO: IO[SecurityDirectives.AuthenticationResult[Nothing]] =
    IO.pure(AppChallenge)
}
