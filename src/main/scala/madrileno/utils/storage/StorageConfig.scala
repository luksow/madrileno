package madrileno.utils.storage

import cats.effect.{IO, Resource}
import fs2.io.file.Path as FsPath
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

final case class DiskConfig(root: String) derives ConfigReader

final case class S3ConfigDerivable(
  endpoint: String,
  region: String,
  bucket: String,
  accessKeyId: String,
  secretAccessKey: String)
    derives ConfigReader {
  def toS3Config: S3Config = S3Config(endpoint, region, bucket, accessKeyId, secretAccessKey)
}

final case class StorageConfig(
  backend: String,
  maxUploadBytes: Long,
  signedUrlTtl: FiniteDuration,
  disk: DiskConfig,
  objectStorage: S3ConfigDerivable)
    derives ConfigReader

object StorageRuntime {
  def fromConfig(config: StorageConfig): Resource[IO, ObjectStoreRuntime] = config.backend match {
    case "disk" => Resource.pure(ObjectStoreRuntime.disk(FsPath(config.disk.root)))
    case "s3"   => ObjectStoreRuntime.s3(config.objectStorage.toS3Config)
    case other  => Resource.eval(IO.raiseError(new IllegalArgumentException(s"Unknown storage backend: $other (expected 'disk' or 's3')")))
  }
}
