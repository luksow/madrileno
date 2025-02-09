package madrileno.healthcheck.routers

import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.routers.dto.HealthCheckResponse
import madrileno.healthcheck.services.HealthCheckService
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.server.Route

class HealthCheckRouter(healthCheckService: HealthCheckService) extends BaseRouter {
  val routes: Route = (get & path("health-check") & pathEndOrSingleSlash) {
    complete {
      healthCheckService.healthCheck().map { appConfig => Ok -> HealthCheckResponse(appConfig) }
    }
  }

  def authRoutes(auth: AuthContext): Route = {
    (get & path("health-check") & pathEndOrSingleSlash) {
      complete {
        healthCheckService.healthCheck(auth).map { case (appConfig, userId, rtt, externalConnectionResult) =>
          Ok -> HealthCheckResponse(appConfig, userId, rtt, externalConnectionResult)
        }
      }
    }
  }
}
