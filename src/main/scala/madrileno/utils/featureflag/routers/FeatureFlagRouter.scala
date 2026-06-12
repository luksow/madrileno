package madrileno.utils.featureflag.routers

import madrileno.auth.domain.AuthContext
import madrileno.utils.featureflag.domain.{EvaluationContext, TargetingKey}
import madrileno.utils.featureflag.routers.dto.ClientFlagsDto
import madrileno.utils.featureflag.services.FeatureFlagServiceLive
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class FeatureFlagRouter(service: FeatureFlagServiceLive) extends BaseRouter {

  def authedRoutes(auth: AuthContext): Route =
    (get & path("feature-flags") & pathEndOrSingleSlash) {
      complete {
        val ctx = EvaluationContext.anonymous(TargetingKey(auth.userId.toString))
        service
          .evaluateClientExposed(ctx)
          .map[ToResponseMarshallable](flags => Ok -> ClientFlagsDto(flags.map { case (key, value) => key.unwrap -> value }))
      }
    }
}
