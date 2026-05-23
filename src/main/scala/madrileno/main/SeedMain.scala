package madrileno.main

import cats.effect.{Clock, IO, IOApp}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.{PgConfig, PgTransactor, Transactor}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource

import java.util.UUID

object SeedMain extends IOApp.Simple {

  override def run: IO[Unit] = {
    given Meter[IO]  = Meter.Implicits.noop
    given Tracer[IO] = Tracer.Implicits.noop

    for {
      config    <- IO.delay(ConfigSource.default)
      appConfig <- IO.delay(config.at("app").loadOrThrow[AppConfig])
      pgConfig  <- IO.delay(config.at("pg").loadOrThrow[PgConfig])
      _ <- IO.raiseUnless(appConfig.environment == Environment.Dev)(
             new IllegalStateException(s"SeedMain refuses to run with app.environment=${appConfig.environment}. Dev only.")
           )
      _ <- PgTransactor.resource(pgConfig).use { transactor =>
             seed(transactor, new UserRepository)
           }
    } yield ()
  }

  private def seed(transactor: Transactor, userRepository: UserRepository): IO[Unit] = {
    val demoUser = User(
      id = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
      fullName = Some(FullName("Demo User")),
      emailAddress = Some(EmailAddress("demo@example.com")),
      emailVerified = true,
      avatarUrl = None,
      blockedAt = None
    )
    for {
      now <- Clock[IO].realTimeInstant
      created <- transactor.inTransaction {
                   userRepository.findIncludingDeleted(demoUser.id).flatMap {
                     case Some(_) => IO.pure(false)
                     case None    => userRepository.create(demoUser, now).as(true)
                   }
                 }
      _ <- IO.println(
             if (created) s"created demo user ${demoUser.id}"
             else s"demo user ${demoUser.id} already present, skipping"
           )
    } yield ()
  }
}
