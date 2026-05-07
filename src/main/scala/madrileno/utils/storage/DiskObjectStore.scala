package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import org.http4s.Header
import org.http4s.headers.`Content-Type`

import scala.concurrent.duration.FiniteDuration

class DiskObjectStore(root: FsPath) extends ObjectStore {

  override def put(
    key: StorageKey,
    metadata: ObjectMetadata,
    body: Stream[IO, Byte]
  ): IO[Unit] = {
    val target  = pathFor(key)
    val ctValue = Header[`Content-Type`].value(metadata.contentType)
    Files[IO].createDirectories(target.parent.getOrElse(root)) *>
      body.through(Files[IO].writeAll(target)).compile.drain *>
      Stream
        .emits(s"$ctValue\n${metadata.sizeBytes}\n".getBytes("UTF-8"))
        .through(Files[IO].writeAll(metaPathFor(key)))
        .compile
        .drain
  }

  override def get(
    key: StorageKey,
    ttl: FiniteDuration,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult] = {
    val _ = (ttl, fileName) // disk streams locally — caller sets Content-Disposition itself
    readMeta(key).map {
      case Some((contentType, _)) => ObjectStore.GetResult.Streamed(contentType, Files[IO].readAll(pathFor(key)))
      case None                   => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    Files[IO].deleteIfExists(pathFor(key)) *> Files[IO].deleteIfExists(metaPathFor(key)).void

  private def pathFor(key: StorageKey): FsPath     = root / key.unwrap
  private def metaPathFor(key: StorageKey): FsPath = root / s"${key.unwrap}.meta"

  private def readMeta(key: StorageKey): IO[Option[(`Content-Type`, Long)]] = {
    val path = metaPathFor(key)
    Files[IO].exists(path).flatMap {
      case false => IO.pure(None)
      case true =>
        Files[IO]
          .readAll(path)
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .map { contents =>
            contents.split('\n').toList match {
              case ct :: size :: _ =>
                `Content-Type`.parse(ct).toOption.map(_ -> size.trim.toLong)
              case _ => None
            }
          }
    }
  }
}
