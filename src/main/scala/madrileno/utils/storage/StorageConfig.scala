package madrileno.utils.storage

import pureconfig.ConfigReader

final case class S3ConfigDerivable(
  endpoint: String,
  region: String,
  bucket: String,
  accessKeyId: String,
  secretAccessKey: String)
    derives ConfigReader {
  def toS3Config: S3Config = S3Config(endpoint, region, bucket, accessKeyId, secretAccessKey)
}

final case class StorageConfig(maxUploadBytes: Long, objectStorage: S3ConfigDerivable) derives ConfigReader
