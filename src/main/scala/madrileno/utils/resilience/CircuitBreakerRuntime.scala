package madrileno.utils.resilience

import cats.effect.kernel.Deferred
import cats.effect.{IO, Ref}
import io.chrisdavenport.circuit.{Backoff, CircuitBreaker}

import java.util.concurrent.CancellationException
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

trait CircuitBreakerRuntime {
  def create(
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    backoff: FiniteDuration => FiniteDuration = Backoff.exponential,
    maxResetTimeout: Duration = 1.minute
  ): IO[CircuitBreaker[IO]]
}

object CircuitBreakerRuntime {

  def default: CircuitBreakerRuntime = new CircuitBreakerRuntime {
    override def create(
      maxFailures: Int,
      resetTimeout: FiniteDuration,
      backoff: FiniteDuration => FiniteDuration,
      maxResetTimeout: Duration
    ): IO[CircuitBreaker[IO]] =
      memoize(CircuitBreaker.of[IO](maxFailures, resetTimeout, backoff, maxResetTimeout))
  }

  private def memoize[A](init: IO[A]): IO[A] = {
    val ref = Ref.unsafe[IO, Option[Deferred[IO, Either[Throwable, A]]]](None)
    Deferred[IO, Either[Throwable, A]].flatMap { fresh =>
      IO.uncancelable { poll =>
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
