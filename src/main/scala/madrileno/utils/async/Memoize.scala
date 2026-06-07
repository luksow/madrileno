package madrileno.utils.async

import cats.effect.kernel.Deferred
import cats.effect.{IO, Ref}

import java.util.concurrent.CancellationException

object Memoize {
  def apply[A](init: IO[A]): IO[A] = {
    val ref = Ref.unsafe[IO, Option[Deferred[IO, Either[Throwable, A]]]](None)
    IO.uncancelable { poll =>
      ref.get.flatMap {
        case Some(d) => poll(d.get).rethrow
        case None =>
          Deferred[IO, Either[Throwable, A]].flatMap { fresh =>
            ref.modify {
              case Some(d) => (Some(d), poll(d.get).rethrow)
              case None =>
                val cancellation = new CancellationException("memoize: init cancelled")
                val attempt =
                  poll(init).attempt
                    .flatTap(fresh.complete(_).void)
                    .flatTap {
                      case Left(_)  => ref.set(None)
                      case Right(_) => IO.unit
                    }
                    .onCancel(ref.set(None) *> fresh.complete(Left(cancellation)).void)
                    .rethrow
                (Some(fresh), attempt)
            }.flatten
          }
      }
    }
  }
}
