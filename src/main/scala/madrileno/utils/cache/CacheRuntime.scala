package madrileno.utils.cache

import cats.effect.IO
import com.github.blemale.scaffeine.{Cache as ScaffeineCache, Scaffeine}

import java.util.concurrent.ConcurrentHashMap
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

  // For tests: no TTL, no eviction, just a map.
  def inMemory: CacheRuntime = new CacheRuntime {
    override def expiring[K, V](expireAfterWrite: FiniteDuration, maxSize: Long): Cache[K, V] =
      new MapBackedCache[K, V]
  }

  private class ScaffeineBackedCache[K, V](underlying: ScaffeineCache[K, V]) extends Cache[K, V] {
    override def get(key: K): IO[Option[V]]      = IO.delay(underlying.getIfPresent(key))
    override def put(key: K, value: V): IO[Unit] = IO.delay(underlying.put(key, value))
    override def invalidate(key: K): IO[Unit]    = IO.delay(underlying.invalidate(key))
    override def invalidateAll: IO[Unit]         = IO.delay(underlying.invalidateAll())
    override def size: IO[Long]                  = IO.delay(underlying.estimatedSize())
  }

  private class MapBackedCache[K, V] extends Cache[K, V] {
    private val store = new ConcurrentHashMap[K, V]()

    override def get(key: K): IO[Option[V]]      = IO.delay(Option(store.get(key)))
    override def put(key: K, value: V): IO[Unit] = IO.delay(store.put(key, value)).void
    override def invalidate(key: K): IO[Unit]    = IO.delay(store.remove(key)).void
    override def invalidateAll: IO[Unit]         = IO.delay(store.clear())
    override def size: IO[Long]                  = IO.delay(store.size().toLong)
  }
}
