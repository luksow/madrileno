package madrileno.support

import cats.effect.IO
import madrileno.utils.cache.{Cache, CacheRuntime}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

object TestCacheRuntime {
  def unbounded: CacheRuntime = new CacheRuntime {
    override def expiring[K, V](expireAfterWrite: FiniteDuration, maxSize: Long): Cache[K, V] =
      new MapBackedCache[K, V]
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
