package madrileno.utils.observability.admin

import cats.effect.IO
import madrileno.utils.db.transactor.{PgConfig, Transactor}
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

class DbAdminRouter(transactor: Transactor, pgConfig: PgConfig) extends BaseRouter {

  val routes: Route =
    (get & pathPrefix("db") & pathEndOrSingleSlash) {
      complete(status().map[ToResponseMarshallable](Ok -> _))
    }

  private def status(): IO[DbStatusDto] =
    transactor
      .inSession {
        val session = summon[Session[IO]]
        session.execute(DbAdminRouter.connectionsByState)
      }
      .map { rows =>
        val byState = rows.collect { case (Some(state), count) => state -> count }.toMap
        DbStatusDto(
          config = DbConfigDto(host = pgConfig.host, port = pgConfig.port, database = pgConfig.database, maxPoolSize = pgConfig.max),
          connections = ConnectionsDto(
            active = byState.getOrElse("active", 0L),
            idle = byState.getOrElse("idle", 0L),
            idleInTransaction = byState.getOrElse("idle in transaction", 0L) + byState.getOrElse("idle in transaction (aborted)", 0L),
            total = byState.values.sum
          )
        )
      }
}

object DbAdminRouter {
  private val connectionsByState: Query[Void, (Option[String], Long)] =
    sql"""
      SELECT state, COUNT(*)::int8
      FROM pg_stat_activity
      WHERE datname = current_database()
      GROUP BY state
    """.query(text.opt *: int8)
}
