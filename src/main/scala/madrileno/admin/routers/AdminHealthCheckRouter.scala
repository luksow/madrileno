package madrileno.admin.routers

import madrileno.admin.routers.dto.{AdminHealthCheckDto, DepStatus}
import madrileno.admin.services.AdminHealthCheckService
import madrileno.utils.http.BaseRouter
import org.http4s.Status
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AdminHealthCheckRouter(adminHealthCheckService: AdminHealthCheckService) extends BaseRouter {
  val routes: Route = (get & path("health-check") & pathEndOrSingleSlash) {
    complete {
      adminHealthCheckService.check().map[ToResponseMarshallable] { dto =>
        val status: Status = if (dto.status == DepStatus.Up) Ok else ServiceUnavailable
        status -> dto
      }
    }
  }
}
