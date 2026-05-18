package __package__.__aggregate__.routers

import __package__.auth.domain.AuthContext
import __package__.__aggregate__.domain.__Aggregate__Id
import __package__.__aggregate__.routers.dto.__Aggregate__Dto
import __package__.__aggregate__.services.__Aggregate__Service
import __package__.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class __Aggregate__Router(__aggregate__Service: __Aggregate__Service) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    val _ = authContext
    (get & path("__aggregates__" / JavaUUID.as[__Aggregate__Id]) & pathEndOrSingleSlash) { id =>
      complete {
        __aggregate__Service.get(id).map[ToResponseMarshallable](e => Ok -> __Aggregate__Dto(e))
      }
    }
  }
}
