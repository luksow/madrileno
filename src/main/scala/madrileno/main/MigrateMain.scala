package madrileno.main

import cats.effect.{ExitCode, IO, IOApp}
import madrileno.utils.db.Migrations
import madrileno.utils.db.transactor.PgConfig
import pureconfig.*

object MigrateMain extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      pgConfig <- IO.delay(ConfigSource.default.at("pg").loadOrThrow[PgConfig])
      result   <- IO.blocking(Migrations.flyway(pgConfig).migrate())
      _ <-
        IO.println(
          s"flyway: applied ${result.migrationsExecuted} migration(s); schema now at v${Option(result.targetSchemaVersion).map(_.toString).getOrElse("?")}"
        )
    } yield if (result.success) ExitCode.Success else ExitCode.Error
}
