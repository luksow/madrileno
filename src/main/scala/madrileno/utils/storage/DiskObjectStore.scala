package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import org.http4s.headers.`Content-Type`
import org.http4s.{Headers, MediaType, Response, Status}

import scala.concurrent.duration.FiniteDuration

class DiskObjectStore(root: FsPath) extends ObjectStore {

  override def put(
    key: StorageKey,
    contentType: String,
    sizeBytes: Long,
    content: Stream[IO, Byte]
  ): IO[Unit] = {
    val target = pathFor(key)
    Files[IO].createDirectories(target.parent.getOrElse(root)) *>
      content.through(Files[IO].writeAll(target)).compile.drain *>
      Stream.emits(s"$contentType\n$sizeBytes\n".getBytes("UTF-8")).through(Files[IO].writeAll(metaPathFor(key))).compile.drain
  }

  override def serve(key: StorageKey, ttl: FiniteDuration): IO[Response[IO]] = {
    val _ = ttl
    readMeta(key).flatMap {
      case Some((contentType, _)) =>
        val media = MediaType.parse(contentType).getOrElse(MediaType.application.`octet-stream`)
        IO.pure(Response[IO](Status.Ok, headers = Headers(`Content-Type`(media)), body = Files[IO].readAll(pathFor(key))))
      case None => IO.pure(Response[IO](Status.NotFound))
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    Files[IO].deleteIfExists(pathFor(key)) *> Files[IO].deleteIfExists(metaPathFor(key)).void

  private def pathFor(key: StorageKey): FsPath     = root / key.unwrap
  private def metaPathFor(key: StorageKey): FsPath = root / s"${key.unwrap}.meta"

  private def readMeta(key: StorageKey): IO[Option[(String, Long)]] = {
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
              case ct :: size :: _ => Some(ct -> size.trim.toLong)
              case _               => None
            }
          }
    }
  }

}
