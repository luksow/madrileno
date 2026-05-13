package madrileno.auth.services

import cats.effect.IO
import madrileno.auth.domain.*
import madrileno.user.domain.EmailAddress

object DevAuthVerifier extends ExternalAuthVerifier {
  override def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] =
    IO.pure {
      EmailAddress.validate(token.trim) match {
        case Right(email) =>
          Right(
            VerifiedExternalToken(
              Provider.Dev,
              ProviderUserId(email.unwrap),
              Credential(email.unwrap),
              ExternalProfile(fullName = None, emailAddress = Some(email), emailVerified = true, avatarUrl = None),
              Metadata.empty
            )
          )
        case Left(reason) =>
          Left(new IllegalArgumentException(s"dev auth token must be an email address: $reason"))
      }
    }
}
