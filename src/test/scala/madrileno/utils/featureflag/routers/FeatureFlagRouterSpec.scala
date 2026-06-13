package madrileno.utils.featureflag.routers

import cats.effect.IO
import io.circe.Json
import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.routers.dto.ClientFlagsDto
import madrileno.utils.featureflag.services.{CreateFlagCommand, CreateFlagResult, CreateSegmentCommand, RuleData}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Headers, Request, Uri}
import pl.iterators.baklava.{FreeFormSchema, Schema}
import pl.iterators.stir.server.Route

class FeatureFlagRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  // evaluated flag values are arbitrary JSON — no derivable OpenAPI shape
  private given Schema[Json] = FreeFormSchema("FlagValue")

  private val auth = AuthContext(TestData.user())

  private def seedFlag(key: String, clientExposed: Boolean): FeatureFlag = {
    val command =
      CreateFlagCommand(FlagKey(key), FlagDescription(""), enabled = true, FlagVariant.BoolVariant(true), clientExposed, Nil, Actor("router-spec"))
    application.featureFlagService.createFlag(command).unsafeRunSync() match {
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
          response.body.flags.get(FlagKey("bootstrap-exposed")) shouldBe Some(Json.True)
          response.body.flags.keySet should not contain FlagKey("bootstrap-hidden")
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  describe("attribute-based targeting in the bootstrap") {
    it("returns a verified-users-targeted flag as true for verified users and false otherwise") {
      val segment = SegmentName("verified-users")
      val _ = application.featureFlagService
        .createSegment(CreateSegmentCommand(segment, FlagDescription(""), List(RuleCondition.StringEquals(AttributeName("emailVerified"), "true"))))
        .unsafeRunSync()
      val rule = RuleData(
        RulePosition(0),
        FlagDescription(""),
        List(RuleCondition.SegmentMatch(segment)),
        RuleOutcome.FixedValue(FlagVariant.BoolVariant(true))
      )
      val _ = application.featureFlagService
        .createFlag(
          CreateFlagCommand(
            FlagKey("bootstrap-verified-only"),
            FlagDescription(""),
            enabled = true,
            FlagVariant.BoolVariant(false),
            clientExposed = true,
            List(rule),
            Actor("router-spec")
          )
        )
        .unsafeRunSync()

      def flagFor(auth: AuthContext): Option[Json] = {
        val request = Request[IO](
          method = GET,
          uri = Uri.unsafeFromString("/v1/feature-flags"),
          headers = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, validJwt(auth))))
        )
        allRoutes.orNotFound.run(request).flatMap(_.as[ClientFlagsDto]).unsafeRunSync().flags.get(FlagKey("bootstrap-verified-only"))
      }

      flagFor(AuthContext(TestData.user(emailVerified = true))) shouldBe Some(Json.True)
      flagFor(AuthContext(TestData.user(emailVerified = false))) shouldBe Some(Json.False)
    }
  }
}
