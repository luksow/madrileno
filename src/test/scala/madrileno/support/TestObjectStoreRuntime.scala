package madrileno.support

import cats.effect.{IO, Ref}
import fs2.Stream
import madrileno.utils.storage.{ObjectStore, ObjectStoreRuntime, StorageKey}
import org.http4s.headers.`Content-Type`
import org.http4s.{Headers, MediaType, Response, Status}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

object TestObjectStoreRuntime {

  def inMemory: ObjectStoreRuntime = {
    val state = Ref.unsafe[IO, Map[StorageKey, (String, ByteVector)]](Map.empty)

    new ObjectStoreRuntime {
      override val objectStore: ObjectStore = new ObjectStore {
        override def put(
          key: StorageKey,
          contentType: String,
          sizeBytes: Long,
          content: Stream[IO, Byte]
        ): IO[Unit] = {
          val _ = sizeBytes
          content.compile.to(ByteVector).flatMap(bytes => state.update(_.updated(key, contentType -> bytes)))
        }

        override def serve(key: StorageKey, ttl: FiniteDuration): IO[Response[IO]] = {
          val _ = ttl
          state.get.map(_.get(key)).map {
            case Some((ct, bytes)) =>
              val media = MediaType.parse(ct).getOrElse(MediaType.application.`octet-stream`)
              Response[IO](Status.Ok, headers = Headers(`Content-Type`(media)), body = Stream.chunk(fs2.Chunk.byteVector(bytes)))
            case None => Response[IO](Status.NotFound)
          }
        }

        override def delete(key: StorageKey): IO[Unit] = state.update(_ - key)
      }
    }
  }
}
