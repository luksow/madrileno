package madrileno.auth.services

import cats.effect.IO
import com.google.api.core.{ApiFuture, ApiFutureCallback, ApiFutures}
import com.google.common.util.concurrent.MoreExecutors
import com.google.firebase.auth.FirebaseAuth
import io.circe.parser
import madrileno.auth.domain.*
import madrileno.user.domain.*

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.CompletableFuture

class FirebaseService(firebase: FirebaseAuth) extends ExternalAuthVerifier {

  def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] =
    apiFutureToIO(firebase.verifyIdTokenAsync(token)).map { firebaseToken =>
      val metadata = firebaseTokenToMetadata(token)

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
    }.attempt

  private def firebaseTokenToMetadata(firebaseJwt: String): Metadata = {
    val parts =
      firebaseJwt.split("\\.")

    val claimsPart =
      parts.lift(1).getOrElse {
        throw new IllegalArgumentException("Invalid Firebase JWT structure")
      }

    val claimsInJson =
      new String(Base64.getUrlDecoder.decode(claimsPart), UTF_8)

    val claims =
      parser.parse(claimsInJson).fold(err => throw new IllegalStateException(s"Invalid Firebase JWT claims: ${err.message}", err), identity)

    Metadata(claims)
  }

  private def apiFutureToIO[A](future: ApiFuture[A]): IO[A] = {
    val cf = new CompletableFuture[A]()

    ApiFutures.addCallback(
      future,
      new ApiFutureCallback[A] {
        override def onSuccess(result: A): Unit =
          cf.complete(result): Unit

        override def onFailure(t: Throwable): Unit =
          cf.completeExceptionally(t): Unit
      },
      MoreExecutors.directExecutor()
    )

    IO.fromCompletableFuture(IO.pure(cf))
  }
}
