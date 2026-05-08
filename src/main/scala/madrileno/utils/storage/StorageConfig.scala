package madrileno.utils.storage

import pureconfig.ConfigReader

final case class StorageConfig(objectStorage: S3Config) derives ConfigReader
