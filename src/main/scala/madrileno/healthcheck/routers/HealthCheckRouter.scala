package madrileno.healthcheck.routers

import madrileno.auth.domain.AuthContext
import madrileno.healthcheck.routers.dto.HealthCheckDto
import madrileno.healthcheck.services.{GetHealthCheckCommand, HealthCheckService}
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
        val command = GetHealthCheckCommand(auth.userId)
        healthCheckService.healthCheck(command).map { case (appConfig, userId, rtt) =>
          Ok -> HealthCheckDto(appConfig, userId, rtt)
        }
      }
    }
  }
}
