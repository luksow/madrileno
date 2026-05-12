package madrileno.utils.db

import cats.effect.IO
import madrileno.utils.db.transactor.PgConfig
import madrileno.utils.observability.LoggingSupport
import org.flywaydb.core.Flyway

object Migrations extends LoggingSupport {
  def flyway(pg: PgConfig): Flyway =
    Flyway.configure().dataSource(pg.jdbcUrl, pg.user, pg.password.orNull).locations("classpath:db/migration").load()

  def warnIfPending(pg: PgConfig): IO[Unit] =
    IO.blocking(flyway(pg).info().pending().toList).attempt.flatMap {
      case Right(Nil) => IO.unit
      case Right(pending) =>
        val versions = pending.map(m => s"${m.getVersion} - ${m.getDescription}").mkString(", ")
        loggerWithoutTracing.warn(
          s"""⚠ ${pending.size} pending DB migration(s): $versions. Run `sbt "runMain madrileno.main.MigrateMain"` """ +
            "(or `bin/migrate-main` in the image) — DB requests will fail until then."
        )
      case Left(t) => loggerWithoutTracing.warn(t)("couldn't check for pending DB migrations")
    }
}
