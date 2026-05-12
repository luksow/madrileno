package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.domain.*
import madrileno.user.domain.EmailAddress

object DevAuthVerifier extends ExternalAuthVerifier {
  override def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] =
    IO.pure {
      val email = token.trim
      if (email.contains("@"))
        Right(
          VerifiedExternalToken(
            Provider.Dev,
            ProviderUserId(email),
            Credential(email),
            ExternalProfile(fullName = None, emailAddress = Some(EmailAddress(email)), emailVerified = true, avatarUrl = None),
            Metadata.empty
          )
        )
      else Left(new IllegalArgumentException(s"dev auth token must be an email address (got '$token')"))
    }
}
