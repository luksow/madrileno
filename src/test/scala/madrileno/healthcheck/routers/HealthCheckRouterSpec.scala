package madrileno.healthcheck.routers

import cats.effect.IO
import madrileno.healthcheck.routers.dto.HealthCheckDto
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.json.JsonProtocol.*
import org.http4s.*
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.stir.server.Route

class HealthCheckRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes

  path("/v1/health-check")(
    supports(GET, description = "Health check endpoint (unauthenticated)", summary = "Returns basic application info", tags = Seq("Health Check"))(
      onRequest()
        .respondsWith[HealthCheckDto](Ok, description = "Application info")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name should not be empty
          response.body.version should not be empty
          response.body.userId shouldBe None
          response.body.db shouldBe None
        }
    ),
    supports(
      GET,
      description = "Health check endpoint (authenticated)",
      summary = "Returns application info with DB status",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Health Check")
    )(
      onRequest(security = bearer.apply(validJwt(TestData.authContext())))
        .respondsWith[HealthCheckDto](Ok, description = "Application info with DB status")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name should not be empty
          response.body.userId shouldBe defined
          response.body.db shouldBe defined
        }
    )
  )
}
