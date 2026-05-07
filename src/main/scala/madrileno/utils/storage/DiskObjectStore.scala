package madrileno.utils.storage

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path as FsPath}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.http4s.Header
import org.http4s.headers.`Content-Type`

class DiskObjectStore(root: FsPath) extends ObjectStore {
  import DiskObjectStore.given

  override def put(
    key: StorageKey,
    metadata: ObjectMetadata,
    body: Stream[IO, Byte]
  ): IO[Unit] = {
    val target = pathFor(key)
    Files[IO].createDirectories(target.parent.getOrElse(root)) *>
      body.through(Files[IO].writeAll(target)).compile.drain *>
      Stream
        .emits(metadata.asJson.noSpaces.getBytes("UTF-8"))
        .through(Files[IO].writeAll(metaPathFor(key)))
        .compile
        .drain
  }

  override def get(
    key: StorageKey,
    ttl: SignedUrlTtl,
    fileName: Option[String]
  ): IO[ObjectStore.GetResult] = {
    val _ = ttl
    readMeta(key).map {
      case Some(metadata) => ObjectStore.GetResult.Streamed(metadata.contentType, fileName, Files[IO].readAll(pathFor(key)))
      case None           => ObjectStore.GetResult.NotFound
    }
  }

  override def delete(key: StorageKey): IO[Unit] =
    Files[IO].deleteIfExists(pathFor(key)) *> Files[IO].deleteIfExists(metaPathFor(key)).void

  private def pathFor(key: StorageKey): FsPath     = root / key.render
  private def metaPathFor(key: StorageKey): FsPath = root / s"${key.render}.meta"

  private def readMeta(key: StorageKey): IO[Option[ObjectMetadata]] = {
    val path = metaPathFor(key)
    Files[IO].exists(path).flatMap {
      case false => IO.pure(None)
      case true =>
        Files[IO].readAll(path).through(fs2.text.utf8.decode).compile.string.map(s => decode[ObjectMetadata](s).toOption)
    }
  }
}

object DiskObjectStore {
  given Encoder[`Content-Type`] = Encoder[String].contramap(Header[`Content-Type`].value)
  given Decoder[`Content-Type`] = Decoder[String].emap(s => `Content-Type`.parse(s).left.map(_.message))
  given Encoder[ObjectMetadata] = Encoder.AsObject.derived
  given Decoder[ObjectMetadata] = Decoder.derived
}
