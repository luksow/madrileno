package madrileno.healthcheck.services

import cats.effect.{Clock, IO}
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.main.AppConfig
import madrileno.user.domain.UserId
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import scala.concurrent.duration.FiniteDuration

class HealthCheckService(
  appConfig: AppConfig,
  healthCheckRepository: HealthCheckRepository,
  transactor: Transactor,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {
  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def healthCheck(command: GetHealthCheckCommand): IO[(AppConfig, UserId, Option[FiniteDuration])] = {
    clock
      .timed(transactor.inSession {
        healthCheckRepository.version()
      })
      .map(r => Some(r._1))
      .recoverWith { case t =>
        logger.error(t)("Failed to reach database").map { _ =>
          None
        }
      }
      .map { dbTestResult =>
        (appConfig, command.userId, dbTestResult)
      }
  }
}

case class GetHealthCheckCommand(userId: UserId)
