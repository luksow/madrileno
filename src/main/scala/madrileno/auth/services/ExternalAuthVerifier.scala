package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.domain.VerifiedExternalToken

trait ExternalAuthVerifier {
  def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]]
}
