package madrileno.healthcheck.routers

import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.routers.dto.HealthCheckDto
import madrileno.healthcheck.services.HealthCheckService
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.server.Route

class HealthCheckRouter(healthCheckService: HealthCheckService) extends BaseRouter {
  val routes: Route = (get & path("health-check") & pathEndOrSingleSlash) {
    complete {
      healthCheckService.healthCheck().map { appConfig => Ok -> HealthCheckDto(appConfig) }
    }
  }

  def authRoutes(auth: AuthContext): Route = {
    (get & path("health-check") & pathEndOrSingleSlash) {
      complete {
        healthCheckService.healthCheck(auth).map { case (appConfig, userId, rtt, externalConnectionResult) =>
          Ok -> HealthCheckDto(appConfig, userId, rtt, externalConnectionResult)
        }
      }
    }
  }
}
