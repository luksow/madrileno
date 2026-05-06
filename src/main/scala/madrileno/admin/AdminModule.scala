package madrileno.admin

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.admin.routers.AdminHealthCheckRouter
import madrileno.admin.services.AdminHealthCheckService
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.mailer.MailerConfig
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

trait AdminModule {
  val transactor: Transactor
  val clock: Clock[IO]
  protected def mailerConfig: MailerConfig
  given telemetryContext: TelemetryContext

  private val healthCheckRepository   = wire[HealthCheckRepository]
  private val adminHealthCheckService = wire[AdminHealthCheckService]
  private val adminHealthCheckRouter  = wire[AdminHealthCheckRouter]

  def adminRoute: Route = adminHealthCheckRouter.routes
}
