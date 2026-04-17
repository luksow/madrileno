package madrileno.utils.cache

import cats.effect.IO

trait Cache[K, V] {
  def get(key: K): IO[Option[V]]
  def put(key: K, value: V): IO[Unit]
  def invalidate(key: K): IO[Unit]
  def invalidateAll: IO[Unit]
  def size: IO[Long]
}
