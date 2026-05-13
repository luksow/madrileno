package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.domain.{ExternalAuthToken, Provider, VerifiedExternalToken}

trait ExternalAuthVerifier {
  def verifyToken(token: ExternalAuthToken): IO[Either[Throwable, VerifiedExternalToken]]
}

final class AuthVerifiers(byProvider: Map[Provider, ExternalAuthVerifier]) {
  def get(provider: Provider): Option[ExternalAuthVerifier] = byProvider.get(provider)
  def providers: Set[Provider]                              = byProvider.keySet
}
