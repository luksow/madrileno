package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.Uri
import org.http4s.headers.`Content-Type`
import pl.iterators.kebs.opaque.Opaque

opaque type StorageKey = String
object StorageKey extends Opaque[StorageKey, String] {
  extension (key: StorageKey) def render: String = key.unwrap
}

final case class ObjectMetadata(contentType: `Content-Type`, sizeBytes: Long)

trait ObjectStore {
  def put(
    key: StorageKey,
    metadata: ObjectMetadata,
    body: Stream[IO, Byte]
  ): IO[Unit]
  def get(
    key: StorageKey,
    ttl: SignedUrlTtl,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult]
  def delete(key: StorageKey): IO[Unit]
}

object ObjectStore {
  enum GetResult {
    case Streamed(
      contentType: `Content-Type`,
      fileName: Option[String],
      body: Stream[IO, Byte])
    case Redirected(url: Uri)
    case NotFound
  }
}
