package madrileno.healthcheck.routers

import madrileno.healthcheck.routers.dto.HealthCheckDto
import madrileno.healthcheck.services.HealthCheckService
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.server.Route

class HealthCheckRouter(healthCheckService: HealthCheckService) extends BaseRouter {
  val routes: Route = (get & path("health-check") & pathEndOrSingleSlash) {
    currentApiVersion { v =>
      complete {
        healthCheckService.healthCheck().map { appConfig => Ok -> HealthCheckDto(appConfig, v.urlSegment) }
      }
    }
  }
}
