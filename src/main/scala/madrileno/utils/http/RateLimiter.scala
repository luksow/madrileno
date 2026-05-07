package madrileno.utils.http

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

trait RateLimiter {
  def increment(key: String, window: FiniteDuration): IO[Long]
}
