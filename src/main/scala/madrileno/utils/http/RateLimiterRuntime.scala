package madrileno.utils.http

import cats.effect.IO
import com.github.blemale.scaffeine.{Cache as ScaffeineCache, Scaffeine}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration

trait RateLimiterRuntime {
  def rateLimiter: RateLimiter
}

object RateLimiterRuntime {

  def scaffeine(maxEntriesPerWindow: Long = 100_000L): RateLimiterRuntime = new RateLimiterRuntime {

    private val cachesByWindow = new ConcurrentHashMap[FiniteDuration, ScaffeineCache[String, AtomicLong]]

    private def cacheFor(window: FiniteDuration): ScaffeineCache[String, AtomicLong] =
      cachesByWindow.computeIfAbsent(window, w => Scaffeine().expireAfterWrite(w).maximumSize(maxEntriesPerWindow).build[String, AtomicLong]())

    override val rateLimiter: RateLimiter = (key: String, window: FiniteDuration) =>
      IO.delay(cacheFor(window).get(key, _ => new AtomicLong(0L)).incrementAndGet())
  }
}
