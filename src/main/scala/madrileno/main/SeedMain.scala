package madrileno.main

import cats.effect.{IO, IOApp}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.{PgConfig, PgTransactor, Transactor}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pureconfig.ConfigSource

import java.time.Instant
import java.util.UUID

object SeedMain extends IOApp.Simple {

  def run: IO[Unit] = {
    given Meter[IO]  = Meter.Implicits.noop
    given Tracer[IO] = Tracer.Implicits.noop

    val config    = ConfigSource.default
    val appConfig = config.at("app").loadOrThrow[AppConfig]
    val pgConfig  = config.at("pg").loadOrThrow[PgConfig]

    IO.raiseUnless(appConfig.environment == Environment.Dev)(
      new IllegalStateException(
        s"SeedMain refuses to run with app.environment=${appConfig.environment}. Dev only."
      )
    ).flatMap { _ =>
      PgTransactor.resource(pgConfig).use { transactor =>
        seed(transactor, new UserRepository)
      }
    }
  }

  private def seed(transactor: Transactor, userRepository: UserRepository): IO[Unit] = {
    val demoUser = User(
      id            = UserId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
      fullName      = Some(FullName("Demo User")),
      emailAddress  = Some(EmailAddress("demo@example.com")),
      emailVerified = true,
      avatarUrl     = None,
      blockedAt     = None
    )
    transactor.inSession {
      userRepository.find(demoUser.id).flatMap {
        case Some(_) => IO.println(s"demo user ${demoUser.id} already present, skipping")
        case None    =>
          userRepository.create(demoUser, Instant.now()).void *>
            IO.println(s"created demo user ${demoUser.id}")
      }
    }
  }
}
