package madrileno.support

import cats.effect.IO
import madrileno.utils.http.{RateLimiter, RateLimiterRuntime}

import scala.concurrent.duration.FiniteDuration

object TestRateLimiterRuntime {

  // Tests should not rate-limit themselves by default. `unbounded` always reports a count of zero,
  // so the directive treats every call as under the limit.
  val unbounded: RateLimiterRuntime = new RateLimiterRuntime {
    override val rateLimiter: RateLimiter = (_: String, _: FiniteDuration) => IO.pure(0L)
  }

  // Real Caffeine-backed runtime, in case a spec wants to exercise the limiter end-to-end.
  def caffeine: RateLimiterRuntime = RateLimiterRuntime.caffeine()
}
