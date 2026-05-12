package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.domain.{Provider, VerifiedExternalToken}

class FirebaseService(projectId: String, keyProvider: FirebaseKeyProvider) extends ExternalAuthVerifier {
  private val verifier =
    new Rs256TokenVerifier(
      provider = Provider.Firebase,
      issuer = s"https://securetoken.google.com/$projectId",
      audience = Set(projectId),
      keyResolver = keyProvider.keyFor
    )

  override def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] = verifier.verifyToken(token)
}
