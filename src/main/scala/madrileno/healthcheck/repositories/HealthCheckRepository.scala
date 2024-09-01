package madrileno.healthcheck.repositories

import cats.effect.IO
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

class HealthCheckRepository {
  def version(session: Session[IO]): IO[String] = {
    session.unique(sql"SELECT version()".query(text))
  }
}
