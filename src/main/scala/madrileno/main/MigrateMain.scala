package madrileno.main

import cats.effect.{ExitCode, IO, IOApp}
import madrileno.utils.db.transactor.PgConfig
import org.flywaydb.core.Flyway
import pureconfig.*

object MigrateMain extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      pgConfig <- IO.delay(ConfigSource.default.at("pg").loadOrThrow[PgConfig])
      flyway = Flyway
                 .configure()
                 .dataSource(s"jdbc:postgresql://${pgConfig.host}:${pgConfig.port}/${pgConfig.database}", pgConfig.user, pgConfig.password.orNull)
                 .locations("classpath:db/migration")
                 .load()
      result <- IO.blocking(flyway.migrate())
      _ <-
        IO.println(
          s"flyway: applied ${result.migrationsExecuted} migration(s); schema now at v${Option(result.targetSchemaVersion).map(_.toString).getOrElse("?")}"
        )
    } yield if (result.success) ExitCode.Success else ExitCode.Error
}
