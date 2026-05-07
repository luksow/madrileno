package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.Response
import pl.iterators.kebs.opaque.Opaque

import scala.concurrent.duration.FiniteDuration

opaque type StorageKey = String
object StorageKey extends Opaque[StorageKey, String]

trait ObjectStore {
  def put(
    key: StorageKey,
    contentType: String,
    sizeBytes: Long,
    content: Stream[IO, Byte]
  ): IO[Unit]
  def serve(key: StorageKey, ttl: FiniteDuration): IO[Response[IO]]
  def delete(key: StorageKey): IO[Unit]
}
