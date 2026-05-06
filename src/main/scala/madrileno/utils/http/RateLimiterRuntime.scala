package madrileno.utils.http

import cats.effect.IO
import com.github.blemale.scaffeine.Scaffeine

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration

trait RateLimiterRuntime {
  def rateLimiter: RateLimiter
}

object RateLimiterRuntime {

  // Single in-process counter store. Counter entries expire on their own per-window deadline,
  // so a single cache instance handles arbitrary window sizes without bucketing.
  // Cluster-aware operation requires a Redis-backed `RateLimiter`; this is single-node.
  def caffeine(maxEntries: Long = 100_000L): RateLimiterRuntime = new RateLimiterRuntime {

    private final case class Counter(count: AtomicLong, expiresAtNanos: Long)

    private val cache = Scaffeine()
      .maximumSize(maxEntries)
      .expireAfter[String, Counter](
        create = (_, v) => remaining(v.expiresAtNanos),
        update = (
          _,
          v,
          _
        ) => remaining(v.expiresAtNanos),
        read = (
          _,
          v,
          _
        ) => remaining(v.expiresAtNanos)
      )
      .build[String, Counter]()

    override val rateLimiter: RateLimiter = (key: String, window: FiniteDuration) =>
      IO.delay {
        val now     = System.nanoTime()
        val counter = cache.get(key, _ => Counter(new AtomicLong(0L), now + window.toNanos))
        counter.count.incrementAndGet()
      }

    private def remaining(expiresAtNanos: Long): FiniteDuration = {
      val nanos = math.max(1L, expiresAtNanos - System.nanoTime())
      FiniteDuration(nanos, scala.concurrent.duration.NANOSECONDS)
    }
  }
}
