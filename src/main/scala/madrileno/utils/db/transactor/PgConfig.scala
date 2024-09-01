package madrileno.utils.db.transactor

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader
import skunk.Session

import scala.concurrent.duration.Duration

enum PgConfigSSL {
  case None
  case Trusted
  case System
}

case class PgConfig(
  host: String,
  port: Int = 5432,
  user: String,
  database: String,
  password: Option[String] = None,
  max: Int = 10,
  debug: Boolean = false,
  ssl: PgConfigSSL = PgConfigSSL.None,
  parameters: Map[String, String] = Session.DefaultConnectionParameters,
  commandCache: Int = 1024,
  queryCache: Int = 1024,
  parseCache: Int = 1024,
  readTimeout: Duration = Duration.Inf)

object PgConfig {
  given ConfigReader[PgConfig] = deriveReader[PgConfig]
}
