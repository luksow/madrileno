package madrileno.healthcheck

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.repositories.HealthCheckRepository
import madrileno.healthcheck.routers.HealthCheckRouter
import madrileno.healthcheck.services.HealthCheckService
import madrileno.main.AppConfig
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

trait HealthCheckModule extends RouteProvider with AuthRouteProvider {
  lazy val appConfig: AppConfig
  val transactor: Transactor
  val clock: Clock[IO]
  given telemetryContext: TelemetryContext
  private val healthCheckRepository = wire[HealthCheckRepository]
  private val healthCheckService    = wire[HealthCheckService]
  private val healthCheckRouter     = wire[HealthCheckRouter]

  abstract override def route: Route =
    super.route ~
      healthCheckRouter.routes

  abstract override def route(auth: AuthContext): Route = super.route(auth) ~ healthCheckRouter.authRoutes(auth)
}
