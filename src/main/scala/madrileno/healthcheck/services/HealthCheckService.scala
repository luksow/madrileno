package madrileno.healthcheck.services

import cats.effect.IO
import cats.effect.Clock
import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.main.AppConfig
import madrileno.user.domain.UserId
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.logging.LoggingSupport

import scala.concurrent.duration.FiniteDuration

class HealthCheckService(
  appConfig: AppConfig,
  healthCheckRepository: HealthCheckRepository,
  transactor: Transactor,
  clock: Clock[IO])
    extends LoggingSupport {
  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def healthCheck(authContext: AuthContext): IO[(AppConfig, UserId, Option[FiniteDuration])] = {
    val dbTest = clock.timed(transactor.inSession { s =>
      healthCheckRepository.version(s)
    })
    dbTest
      .map { case (latency, _) =>
        (appConfig, authContext.id, Some(latency))
      }
      .recoverWith { case t =>
        Logger[IO].error(t)("Failed to reach database").map { _ =>
          (appConfig, authContext.id, None)
        }
      }
  }
}
