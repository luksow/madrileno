package madrileno.healthcheck.services

import cats.effect.IO
import cats.effect.Clock
import cats.syntax.parallel._
import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.gateways.FingerprintingApiGateway
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.main.AppConfig
import madrileno.user.domain.UserId
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import scala.concurrent.duration.FiniteDuration

class HealthCheckService(
  appConfig: AppConfig,
  healthCheckRepository: HealthCheckRepository,
  fingerprintingApiGateway: FingerprintingApiGateway,
  transactor: Transactor,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {
  def healthCheck(): IO[AppConfig] = IO.pure(appConfig)

  def healthCheck(authContext: AuthContext): IO[(AppConfig, UserId, Option[FiniteDuration], Option[String])] = {
    val dbTest = clock
      .timed(transactor.inSession { s =>
        healthCheckRepository.version(s)
      })
      .map(r => Some(r._1))
      .recoverWith { case t =>
        logger.error(t)("Failed to reach database").map { _ =>
          None
        }
      }
    val externalQueryTest = fingerprintingApiGateway.getIp.map(r => Some(r.ip)).recoverWith { case t =>
      logger.error(t)("Failed to reach external service").map { _ =>
        None
      }
    }
    (dbTest, externalQueryTest).parTupled.map { (dbTestResult, externalQueryResult) =>
      (appConfig, authContext.id, dbTestResult, externalQueryResult)
    }
  }
}
