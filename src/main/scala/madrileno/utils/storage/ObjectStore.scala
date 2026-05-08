package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import org.http4s.Uri
import org.http4s.headers.`Content-Type`
import pl.iterators.kebs.opaque.Opaque
import scodec.bits.ByteVector

opaque type StorageKey = String
object StorageKey extends Opaque[StorageKey, String] {
  override def validate(value: String): Either[String, StorageKey] =
    if (value.isEmpty) Left("StorageKey must not be empty")
    else if (value.startsWith("/") || value.startsWith("\\")) Left("StorageKey must be relative")
    else if (value.split(Array('/', '\\')).contains("..")) Left("StorageKey must not contain '..' segments")
    else if (value.contains('\u0000')) Left("StorageKey must not contain null bytes")
    else Right(value)

  extension (key: StorageKey) def render: String = key.unwrap
}

final case class ObjectStat(sizeBytes: Long, contentType: `Content-Type`)

trait ObjectStore {
  def put(
    key: StorageKey,
    contentType: `Content-Type`,
    body: Stream[IO, Byte]
  ): IO[Long]
  def get(
    key: StorageKey,
    ttl: SignedUrlTtl,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult]
  def delete(key: StorageKey): IO[Unit]
  def presignPut(
    key: StorageKey,
    ttl: SignedUrlTtl,
    contentType: `Content-Type`,
    contentLength: Long
  ): IO[Uri]
  def head(key: StorageKey): IO[Option[ObjectStat]]
  def fetchBytes(key: StorageKey): IO[ByteVector]
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
