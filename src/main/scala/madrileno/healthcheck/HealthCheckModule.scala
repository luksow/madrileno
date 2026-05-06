package madrileno.healthcheck

import com.softwaremill.macwire.*
import madrileno.healthcheck.routers.HealthCheckRouter
import madrileno.healthcheck.services.HealthCheckService
import madrileno.main.AppConfig
import madrileno.utils.http.RouteProvider
import pl.iterators.stir.server.Route

trait HealthCheckModule extends RouteProvider {
  lazy val appConfig: AppConfig
  private val healthCheckService = wire[HealthCheckService]
  private val healthCheckRouter  = wire[HealthCheckRouter]

  abstract override def route: Route =
    super.route ~
      healthCheckRouter.routes
}
