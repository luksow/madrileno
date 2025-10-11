package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.trace.Tracer
import skunk.util.Typer
import skunk.*

class PgTransactor(sessions: Resource[IO, Session[IO]]) extends Transactor {
  override def inTransaction[A](f: DBInTransaction[A]): IO[A] = {
    sessions.use { session =>
      session.transaction.use { transaction =>
        f(using session)(using transaction)
      }
    }
  }

  override def inSession[A](f: DB[A]): IO[A] = {
    sessions.use { session =>
      f(using session)
    }
  }
}

object PgTransactor {
  def resource(pgConfig: PgConfig)(using Tracer[IO]): Resource[IO, PgTransactor] = {
    val session = Session.pooled[IO](
      host = pgConfig.host,
      port = pgConfig.port,
      user = pgConfig.user,
      database = pgConfig.database,
      password = pgConfig.password,
      max = pgConfig.max,
      debug = pgConfig.debug,
      ssl = pgConfig.ssl match {
        case PgConfigSSL.None    => SSL.None
        case PgConfigSSL.Trusted => SSL.Trusted
        case PgConfigSSL.System  => SSL.System
      },
      parameters = pgConfig.parameters,
      commandCache = pgConfig.commandCache,
      queryCache = pgConfig.queryCache,
      parseCache = pgConfig.parseCache,
      readTimeout = pgConfig.readTimeout,
      redactionStrategy = RedactionStrategy.OptIn,
      socketOptions = Session.DefaultSocketOptions,
      strategy = Typer.Strategy.SearchPath
    )
    session.map(new PgTransactor(_))
  }
}
