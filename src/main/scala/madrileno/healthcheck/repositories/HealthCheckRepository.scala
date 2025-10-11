package madrileno.healthcheck.repositories

import cats.effect.IO
import madrileno.utils.db.transactor.DB
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

class HealthCheckRepository {
  def version(): DB[String] = {
    val session = summon[Session[IO]]
    session.unique(sql"SELECT version()".query(text))
  }
}
