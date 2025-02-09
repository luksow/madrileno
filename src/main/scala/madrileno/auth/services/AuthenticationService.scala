package madrileno.auth.services

import cats.effect.IO
import cats.effect.std.UUIDGen
import madrileno.auth.domain.*
import madrileno.user.domain.*
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

class AuthenticationService(firebaseService: FirebaseService, jwtService: JwtService)(using TelemetryContext, UUIDGen[IO]) extends LoggingSupport {
  def authenticateWithFirebase(firebaseToken: FirebaseJwtToken): IO[AuthenticationResult] = {
    firebaseService
      .verifyToken(firebaseToken)
      .flatMap { firebaseToken =>
        UserId.generate
          .map { userId =>
            val authContext = AuthContext(userId, EmailAddress(firebaseToken.getEmail))
            val jwt         = InternalJwt(jwtService.encode(AuthContext.toJson(authContext)))
            AuthenticationResult.UserCreated(jwt)
          }
      }
      .recoverWith { t =>
        logger.error(s"Failed to authenticate with Firebase: ${t.getMessage}").as(AuthenticationResult.InvalidToken)
      }
  }
}

enum AuthenticationResult {
  case Authenticated(jwt: InternalJwt)
  case UserCreated(jwt: InternalJwt)
  case InvalidToken
}
