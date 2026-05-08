package madrileno.support

import cats.effect.{IO, Ref}
import fs2.Stream
import madrileno.utils.storage.{ObjectStore, ObjectStoreRuntime, SignedUrlTtl, StorageKey}
import org.http4s.headers.`Content-Type`
import scodec.bits.ByteVector

object TestObjectStoreRuntime {

  def inMemory: ObjectStoreRuntime = {
    val state = Ref.unsafe[IO, Map[StorageKey, (`Content-Type`, ByteVector)]](Map.empty)

    new ObjectStoreRuntime {
      override val objectStore: ObjectStore = new ObjectStore {
        override def put(
          key: StorageKey,
          contentType: `Content-Type`,
          body: Stream[IO, Byte]
        ): IO[Long] =
          body.compile.to(ByteVector).flatMap(bytes => state.update(_.updated(key, contentType -> bytes)).as(bytes.size.toLong))

        override def get(
          key: StorageKey,
          ttl: SignedUrlTtl,
          fileName: Option[String]
        ): IO[ObjectStore.GetResult] = {
          val _ = ttl
          state.get.map(_.get(key)).map {
            case Some((ct, bytes)) => ObjectStore.GetResult.Streamed(ct, fileName, Stream.chunk(fs2.Chunk.byteVector(bytes)))
            case None              => ObjectStore.GetResult.NotFound
          }
        }

        override def delete(key: StorageKey): IO[Unit] = state.update(_ - key)
      }
    }
  }
}
