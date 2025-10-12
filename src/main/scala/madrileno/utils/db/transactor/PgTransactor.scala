package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.Session.Credentials

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
    val session = Session
      .Builder[IO]
      .withHost(pgConfig.host)
      .withPort(pgConfig.port)
      .withCredentials(Credentials(pgConfig.user, pgConfig.password))
      .withDatabase(pgConfig.database)
      .withDebug(pgConfig.debug)
      .withSSL(pgConfig.ssl match {
        case PgConfigSSL.None    => SSL.None
        case PgConfigSSL.Trusted => SSL.Trusted
        case PgConfigSSL.System  => SSL.System
      })
      .withConnectionParameters(pgConfig.parameters)
      .withCommandCacheSize(pgConfig.commandCache)
      .withQueryCacheSize(pgConfig.queryCache)
      .withParseCacheSize(pgConfig.parseCache)
      .withReadTimeout(pgConfig.readTimeout)
      .withRedactionStrategy(RedactionStrategy.OptIn)
      .withSocketOptions(Session.DefaultSocketOptions)
      .pooled(pgConfig.max)
    session.map(new PgTransactor(_))
  }
}
