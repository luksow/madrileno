package __package__.__aggregate__.routers

import __package__.auth.domain.AuthContext
import __package__.__aggregate__.domain.__Aggregate__Id
import __package__.__aggregate__.routers.dto.__Aggregate__Dto
import __package__.__aggregate__.services.__Aggregate__Service
import __package__.utils.http.BaseRouter
import __package__.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class __Aggregate__Router(__aggregate__Service: __Aggregate__Service)(using TelemetryContext) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    val _ = authContext
    (get & path("__aggregates__" / JavaUUID.as[__Aggregate__Id]) & pathEndOrSingleSlash) { id =>
      complete {
        __aggregate__Service.find(id).map[ToResponseMarshallable] {
          case Some(e) => Ok -> __Aggregate__Dto(e)
          case None    => error(NotFound, "__aggregate__-not-found", "__Aggregate__ not found")
        }
      }
    }
  }
}
