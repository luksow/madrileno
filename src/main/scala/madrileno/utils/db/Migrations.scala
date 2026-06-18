package madrileno.utils.db

import cats.effect.IO
import madrileno.utils.db.transactor.PgConfig
import madrileno.utils.observability.LoggingSupport
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.{CleanResult, MigrateResult, ValidateResult}

object Migrations extends LoggingSupport {
  def flyway(pg: PgConfig): Flyway =
    Flyway.configure().dataSource(pg.jdbcUrl, pg.user, pg.password.orNull).locations("classpath:db/migration").load()

  private def cleanableFlyway(pg: PgConfig): Flyway =
    Flyway
      .configure()
      .dataSource(pg.jdbcUrl, pg.user, pg.password.orNull)
      .locations("classpath:db/migration")
      .cleanDisabled(false)
      .load()

  def migrate(pg: PgConfig): IO[MigrateResult]   = IO.blocking(flyway(pg).migrate())
  def validate(pg: PgConfig): IO[ValidateResult] = IO.blocking(flyway(pg).validateWithResult())
  def clean(pg: PgConfig): IO[CleanResult]       = IO.blocking(cleanableFlyway(pg).clean())

  def info(pg: PgConfig): IO[String] =
    IO.blocking(flyway(pg).info().all().toList).map {
      case Nil => "no migrations found"
      case infos =>
        val rows = infos.map { i =>
          val version = Option(i.getVersion).map(_.getVersion).getOrElse("(repeatable)")
          f"$version%-10s ${i.getState.getDisplayName}%-12s ${i.getDescription}"
        }
        (f"${"Version"}%-10s ${"State"}%-12s Description" :: rows).mkString("\n")
    }

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
