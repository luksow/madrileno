package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import org.http4s.Header
import org.http4s.headers.`Content-Type`
import scodec.bits.ByteVector

import java.nio.file.NoSuchFileException

class DiskObjectStore(root: FsPath, maxFetchBytes: Long) extends ObjectStore {

  override def put(
    key: StorageKey,
    contentType: `Content-Type`,
    body: Stream[IO, Byte]
  ): IO[Long] = {
    val target = pathFor(key)
    for {
      _    <- Files[IO].createDirectories(target.parent.getOrElse(root))
      _    <- Stream.emits(Header[`Content-Type`].value(contentType).getBytes("UTF-8")).through(Files[IO].writeAll(metaPathFor(key))).compile.drain
      _    <- body.through(Files[IO].writeAll(target)).compile.drain
      size <- Files[IO].size(target)
    } yield size
  }

  override def get(
    key: StorageKey,
    ttl: SignedUrlTtl,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult] = {
    val _    = ttl
    val data = pathFor(key)
    for {
      dataExists <- Files[IO].exists(data)
      ct         <- if (dataExists) readContentType(key) else IO.pure(None)
    } yield ct match {
      case Some(contentType) => ObjectStore.GetResult.Streamed(contentType, fileName, Files[IO].readAll(data))
      case None              => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    Files[IO].deleteIfExists(pathFor(key)) *> Files[IO].deleteIfExists(metaPathFor(key)).void

  override def presignPut(
    key: StorageKey,
    ttl: SignedUrlTtl,
    contentType: `Content-Type`,
    contentLength: Long
  ): IO[PresignedPut] = {
    val _ = (key, ttl, contentType, contentLength)
    IO.raiseError(new UnsupportedOperationException("presignPut requires an S3-compatible backend"))
  }

  override def head(key: StorageKey): IO[Option[ObjectStat]] = {
    val data = pathFor(key)
    val stat = for {
      dataExists <- Files[IO].exists(data)
      ct         <- if (dataExists) readContentType(key) else IO.pure(None)
      size       <- if (dataExists) Files[IO].size(data).map(Some(_)) else IO.pure(None)
    } yield (ct, size) match {
      case (Some(c), Some(s)) => Some(ObjectStat(s, c))
      case _                  => None
    }
    stat.recover { case _: NoSuchFileException => None }
  }

  override def fetchBytes(key: StorageKey): IO[Option[ByteVector]] =
    head(key).flatMap {
      case None => IO.pure(None)
      case Some(stat) =>
        val takeLimit = if (maxFetchBytes == Long.MaxValue) Long.MaxValue else maxFetchBytes + 1
        Files[IO]
          .readAll(pathFor(key))
          .take(takeLimit)
          .compile
          .to(ByteVector)
          .flatMap { bytes =>
            if (bytes.size > maxFetchBytes) IO.raiseError(ObjectTooLarge(key, stat.sizeBytes, maxFetchBytes))
            else IO.pure(Some(bytes))
          }
          .recover { case _: NoSuchFileException => None }
    }

  private def pathFor(key: StorageKey): FsPath     = root / key.render
  private def metaPathFor(key: StorageKey): FsPath = root / s"${key.render}.meta"

  private def readContentType(key: StorageKey): IO[Option[`Content-Type`]] = {
    val path = metaPathFor(key)
    Files[IO].exists(path).flatMap {
      case false => IO.pure(None)
      case true =>
        Files[IO].readAll(path).through(fs2.text.utf8.decode).compile.string.map(s => `Content-Type`.parse(s.trim).toOption)
    }
  }
}
