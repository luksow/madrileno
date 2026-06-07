package madrileno.utils.resilience

import cats.effect.IO
import io.chrisdavenport.circuit.{Backoff, CircuitBreaker}
import madrileno.utils.async.Memoize

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
      Memoize(CircuitBreaker.of[IO](maxFailures, resetTimeout, backoff, maxResetTimeout))
  }
}
