package madrileno.admin.services

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.admin.routers.dto.{AdminHealthCheckDto, DepCheck, DepStatus}
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.mailer.MailerConfig
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

import java.net.{InetSocketAddress, Socket}
import scala.concurrent.duration.FiniteDuration

class AdminHealthCheckService(
  healthCheckRepository: HealthCheckRepository,
  transactor: Transactor,
  mailerConfig: MailerConfig,
  clock: Clock[IO]
)(using TelemetryContext)
    extends LoggingSupport {

  def check(): IO[AdminHealthCheckDto] = {
    (postgresCheck, smtpCheck).parTupled.map { case (pg, smtp) =>
      val overall = if (pg.status == DepStatus.Up && smtp.status == DepStatus.Up) DepStatus.Up else DepStatus.Down
      AdminHealthCheckDto(overall, pg, smtp)
    }
  }

  private def postgresCheck: IO[DepCheck] = {
    timed(transactor.inSession(healthCheckRepository.version()))
      .map { case (rtt, _) => DepCheck(DepStatus.Up, Some(rtt.toMillis), None) }
      .handleErrorWith { t =>
        logger.error(t)("Postgres health check failed").as(DepCheck(DepStatus.Down, None, Some(t.getMessage)))
      }
  }

  private val smtpConnectTimeoutMs = 2000

  private def smtpCheck: IO[DepCheck] = {
    timed(IO.blocking {
      val socket = new Socket()
      try socket.connect(new InetSocketAddress(mailerConfig.host, mailerConfig.port), smtpConnectTimeoutMs)
      finally socket.close()
    })
      .map { case (rtt, _) => DepCheck(DepStatus.Up, Some(rtt.toMillis), None) }
      .handleErrorWith { t =>
        logger.error(t)("SMTP health check failed").as(DepCheck(DepStatus.Down, None, Some(t.getMessage)))
      }
  }

  private def timed[A](io: IO[A]): IO[(FiniteDuration, A)] = clock.timed(io)
}
