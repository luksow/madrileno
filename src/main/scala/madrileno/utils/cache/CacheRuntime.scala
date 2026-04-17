package madrileno.utils.cache

import cats.effect.IO
import com.github.blemale.scaffeine.{Cache as ScaffeineCache, Scaffeine}

import scala.concurrent.duration.FiniteDuration

trait CacheRuntime {
  def expiring[K, V](expireAfterWrite: FiniteDuration, maxSize: Long): Cache[K, V]
}

object CacheRuntime {

  def scaffeine: CacheRuntime = new CacheRuntime {
    override def expiring[K, V](expireAfterWrite: FiniteDuration, maxSize: Long): Cache[K, V] = {
      val underlying = Scaffeine()
        .expireAfterWrite(expireAfterWrite)
        .maximumSize(maxSize)
        .build[K, V]()
      new ScaffeineBackedCache(underlying)
    }
  }

  private class ScaffeineBackedCache[K, V](underlying: ScaffeineCache[K, V]) extends Cache[K, V] {
    override def get(key: K): IO[Option[V]]      = IO.delay(underlying.getIfPresent(key))
    override def put(key: K, value: V): IO[Unit] = IO.delay(underlying.put(key, value))
    override def invalidate(key: K): IO[Unit]    = IO.delay(underlying.invalidate(key))
    override def invalidateAll: IO[Unit]         = IO.delay(underlying.invalidateAll())
    override def size: IO[Long]                  = IO.delay(underlying.estimatedSize())
  }
}
