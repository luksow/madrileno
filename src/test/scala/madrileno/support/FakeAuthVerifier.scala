package madrileno.support

import cats.effect.IO
import madrileno.auth.domain.VerifiedExternalToken
import madrileno.auth.services.ExternalAuthVerifier

class FakeAuthVerifier(result: Either[Throwable, VerifiedExternalToken]) extends ExternalAuthVerifier {
  override def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] = IO.pure(result)
}
