package madrileno.utils.storage

import pureconfig.ConfigReader

final case class StorageConfig(maxFetchBytes: Long, objectStorage: S3Config) derives ConfigReader
