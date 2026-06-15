package madrileno.utils.http

import cats.effect.IO
import com.comcast.ip4s.{Cidr, IpAddress}
import com.github.blemale.scaffeine.{Cache as ScaffeineCache, Scaffeine}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration

trait RateLimiterRuntime {
  def rateLimiter: RateLimiter
  def trustedProxies: List[Cidr[IpAddress]] = Nil
}

object RateLimiterRuntime {

  def parseTrustedProxies(raw: String): List[Cidr[IpAddress]] =
    raw.split(",").toList.map(_.trim).filter(_.nonEmpty).map { entry =>
      Cidr
        .fromString(entry)
        .orElse(IpAddress.fromString(entry).map(ip => Cidr(ip, ip.fold(_ => 32, _ => 128))))
        .getOrElse(throw new IllegalArgumentException(s"Invalid CIDR or IP address in rate-limit.trusted-proxies: '$entry'"))
    }

  def scaffeine(maxEntriesPerWindow: Long = 100_000L, trustedProxies: List[Cidr[IpAddress]] = Nil): RateLimiterRuntime = {
    val configuredProxies = trustedProxies
    new RateLimiterRuntime {

      override val trustedProxies: List[Cidr[IpAddress]] = configuredProxies

      private val cachesByWindow = new ConcurrentHashMap[FiniteDuration, ScaffeineCache[String, AtomicLong]]

      private def cacheFor(window: FiniteDuration): ScaffeineCache[String, AtomicLong] =
        cachesByWindow.computeIfAbsent(window, w => Scaffeine().expireAfterWrite(w).maximumSize(maxEntriesPerWindow).build[String, AtomicLong]())

      override val rateLimiter: RateLimiter = (key: String, window: FiniteDuration) =>
        IO.delay(cacheFor(window).get(key, _ => new AtomicLong(0L)).incrementAndGet())
    }
  }
}
