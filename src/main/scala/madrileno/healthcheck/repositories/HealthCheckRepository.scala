package madrileno.healthcheck.repositories

import cats.effect.IO
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

class HealthCheckRepository {
  def version(session: Session[IO]): IO[String] = {
    session.unique(sql"SELECT version()".query(text))
  }
}
