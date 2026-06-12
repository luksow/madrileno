package madrileno.utils.featureflag.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.routers.dto.ClientFlagsDto
import madrileno.utils.featureflag.services.CreateFlagResult
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{FreeFormSchema, Schema}
import pl.iterators.stir.server.Route

class FeatureFlagRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  // evaluated flag values are arbitrary JSON — no derivable OpenAPI shape
  private given Schema[io.circe.Json] = FreeFormSchema("FlagValue")

  private val auth = AuthContext(TestData.user())

  private def seedFlag(key: String, clientExposed: Boolean): FeatureFlag = {
    val flag = TestData.featureFlag(key = FlagKey(key), clientExposed = clientExposed, defaultValue = FlagVariant.BoolVariant(true))
    application.featureFlagService.createFlag(flag, Actor("router-spec")).unsafeRunSync() match {
      case CreateFlagResult.Created(created) => created
      case other                             => fail(s"seeding '$key' failed: $other")
    }
  }

  path("/v1/feature-flags")(
    supports(
      GET,
      description = "Bootstrap payload for clients: every client-exposed flag evaluated against the authenticated user (targeting key = user id). " +
        "Flags not marked client-exposed are never present in the response.",
      summary = "Evaluate all client-exposed flags for the current user",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Feature flags")
    )(
      withSetup {
        val _ = seedFlag("bootstrap-exposed", clientExposed = true)
        seedFlag("bootstrap-hidden", clientExposed = false)
      }.request(_ => onRequest(security = bearer.apply(validJwt(auth))))
        .respondsWith[ClientFlagsDto](Ok, description = "Map of flag key to evaluated value")
        .assert { case (ctx, _) =>
          val response = ctx.performRequest(allRoutes)
          response.body.flags.get("bootstrap-exposed") shouldBe Some(io.circe.Json.True)
          response.body.flags.keySet should not contain "bootstrap-hidden"
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )
}
