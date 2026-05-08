package madrileno.utils.storage

import pureconfig.ConfigReader

final case class StorageConfig(maxFetchBytes: Long, objectStorage: S3Config) derives ConfigReader {
  require(maxFetchBytes >= 0, s"storage.max-fetch-bytes must be >= 0, got $maxFetchBytes")
}
