package madrileno.utils.featureflag.routers

import madrileno.auth.domain.AuthContext
import madrileno.utils.featureflag.domain.EvaluationContext
import madrileno.utils.featureflag.routers.dto.ClientFlagsDto
import madrileno.utils.featureflag.services.FeatureFlagServiceLive
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class FeatureFlagRouter(service: FeatureFlagServiceLive, resolveContext: AuthContext => EvaluationContext) extends BaseRouter {

  def authedRoutes(auth: AuthContext): Route =
    (get & path("feature-flags") & pathEndOrSingleSlash) {
      complete {
        service
          .evaluateClientExposed(resolveContext(auth))
          .map[ToResponseMarshallable](flags => Ok -> ClientFlagsDto(flags))
      }
    }
}
