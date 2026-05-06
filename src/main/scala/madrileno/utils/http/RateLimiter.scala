package madrileno.utils.http

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

final case class RateLimit(to: Long, within: FiniteDuration)

trait RateLimiter {
  def increment(key: String, window: FiniteDuration): IO[Long]
}
