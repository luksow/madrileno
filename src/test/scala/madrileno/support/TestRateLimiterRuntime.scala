package madrileno.support

import cats.effect.IO
import madrileno.utils.http.{RateLimiter, RateLimiterRuntime}

import scala.concurrent.duration.FiniteDuration

object TestRateLimiterRuntime {

  val unbounded: RateLimiterRuntime = new RateLimiterRuntime {
    override val rateLimiter: RateLimiter = (_: String, _: FiniteDuration) => IO.pure(0L)
  }

  def scaffeine: RateLimiterRuntime = RateLimiterRuntime.scaffeine()
}
