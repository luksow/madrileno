package madrileno.auth.services

import cats.effect.IO
import com.google.api.core.ApiFuture
import com.google.firebase.auth.{FirebaseAuth, FirebaseToken}

import java.util.concurrent.Executors

class FirebaseService(firebase: FirebaseAuth) {
  def verifyToken(token: String): IO[FirebaseToken] = {
    apiFutureToIO(firebase.verifyIdTokenAsync(token))
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
