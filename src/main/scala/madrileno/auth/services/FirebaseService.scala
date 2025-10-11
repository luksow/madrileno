package madrileno.auth.services

import cats.effect.IO
import com.google.api.core.ApiFuture
import com.google.firebase.auth.FirebaseAuth
import madrileno.auth.domain.*
import io.circe.parser
import madrileno.user.domain.*
import scala.util.*

import java.net.URI
import java.util.concurrent.Executors

class FirebaseService(firebase: FirebaseAuth) {
  def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] = {
    apiFutureToIO(firebase.verifyIdTokenAsync(token))
      .map { firebaseToken =>
        val metadata = firebaseTokenToMetadata(token)
        Right(
          VerifiedExternalToken(
            Provider.Firebase,
            ProviderUserId(firebaseToken.getUid),
            Credential(firebaseToken.getUid),
            ExternalProfile(
              Option(firebaseToken.getName).map(FullName.apply),
              Option(firebaseToken.getEmail).map(EmailAddress.apply),
              firebaseToken.isEmailVerified,
              Option(firebaseToken.getPicture).map(URI.create)
            ),
            metadata
          )
        )
      }
      .recover { case e: Throwable =>
        Left(e)
      }
  }

  private def firebaseTokenToMetadata(firebaseJwt: String): Metadata = {
    val claimsPart   = firebaseJwt.split("\\.")(1)
    val claimsInJson = new String(java.util.Base64.getUrlDecoder.decode(claimsPart))
    val claims       = parser.parse(claimsInJson).getOrElse(throw new IllegalStateException("Invalid Firebase JWT claims"))
    Metadata(claims)
  }

  private def apiFutureToIO[A](future: ApiFuture[A]): IO[A] = IO.async_[A] { cb =>
    val executor = Executors.newSingleThreadExecutor()
    future.addListener(
      () => {
        try {
          cb(Right(future.get()))
        } catch {
          case e: Exception => cb(Left(e))
        } finally {
          executor.shutdown()
        }
      },
      executor
    )
  }
}
