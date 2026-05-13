package madrileno.support

import cats.effect.IO
import madrileno.auth.domain.{ExternalAuthToken, VerifiedExternalToken}
import madrileno.auth.services.ExternalAuthVerifier

class FakeAuthVerifier(successToken: VerifiedExternalToken, invalidTokenValue: String = "invalid-token") extends ExternalAuthVerifier {
  override def verifyToken(token: ExternalAuthToken): IO[Either[Throwable, VerifiedExternalToken]] =
    if (token == invalidTokenValue) IO.pure(Left(new RuntimeException("Invalid token")))
    else IO.pure(Right(successToken))
}
