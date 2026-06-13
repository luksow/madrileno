package madrileno.utils.featureflag.routers

import madrileno.auth.domain.AuthContext
import madrileno.utils.featureflag.domain.{AttributeName, AttributeValue, EvaluationContext, TargetingKey}
import madrileno.utils.featureflag.routers.dto.ClientFlagsDto
import madrileno.utils.featureflag.services.FeatureFlagServiceLive
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class FeatureFlagRouter(service: FeatureFlagServiceLive) extends BaseRouter {

  def authedRoutes(auth: AuthContext): Route =
    (get & path("feature-flags") & pathEndOrSingleSlash) {
      complete {
        service
          .evaluateClientExposed(contextFor(auth))
          .map[ToResponseMarshallable](flags => Ok -> ClientFlagsDto(flags))
      }
    }

  private def contextFor(auth: AuthContext): EvaluationContext = {
    val attributes = AuthContext
      .toJson(auth)
      .asObject
      .toList
      .flatMap(_.toList)
      .flatMap { case (name, value) =>
        value.asString
          .orElse(value.asBoolean.map(_.toString))
          .orElse(value.asNumber.map(_.toString))
          .map(v => AttributeName(name) -> AttributeValue(v))
      }
      .toMap
    EvaluationContext(TargetingKey(auth.userId.toString), attributes)
  }
}
