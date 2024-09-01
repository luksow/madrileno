package madrileno.healthcheck.routers

import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.routers.dto.HealthCheck
import madrileno.healthcheck.services.HealthCheckService
import madrileno.utils.http.BaseRouter
import madrileno.utils.logging.LoggingSupport
import org.http4s.Status.*
import pl.iterators.stir.server.Route

class HealthCheckRouter(healthCheckService: HealthCheckService) extends BaseRouter with LoggingSupport {
  val routes: Route = (get & path("health-check") & pathEndOrSingleSlash) {
    complete {
      healthCheckService.healthCheck().map { appConfig => Ok -> HealthCheck(appConfig) }
    }
  }

  def authRoutes(auth: AuthContext): Route = {
    (get & path("health-check") & pathEndOrSingleSlash) {
      complete {
        healthCheckService.healthCheck(auth).map { case (appConfig, userId, rtt) =>
          Ok -> HealthCheck(appConfig, userId, rtt)
        }
      }
    }
  }
}
