package __package__.__aggregate__.routers

import __package__.__aggregate__.domain.{__Aggregate__, __Aggregate__Id, __Aggregate__Name}
import __package__.__aggregate__.routers.dto.__Aggregate__Dto
import __package__.auth.domain.AuthContext
import __package__.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import __package__.utils.http.Error
import __package__.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.Instant
import java.util.UUID

class __Aggregate__RouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private val authContext: AuthContext = AuthContext(TestData.user())

  private def seed__Aggregate__(): __Aggregate__ = {
    val entity = __Aggregate__(id = __Aggregate__Id(UUID.randomUUID()), name = __Aggregate__Name("test-name"))
    application.transactor
      .inSession(application.__aggregate__Repository.create(entity, Instant.now()))
      .unsafeRunSync()
  }

  path("/v1/__aggregates__/{id}")(
    supports(
      GET,
      description = "Get a __aggregate__ by id",
      summary = "Returns the __aggregate__ with the given id",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[__Aggregate__Id]("id"),
      tags = Seq("__Aggregates__")
    )(
      withSetup(seed__Aggregate__())
        .request(entity => onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = entity.id))
        .respondsWith[__Aggregate__Dto](Ok, description = "__Aggregate__ found")
        .assert { case (ctx, entity) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe entity.id
          response.body.name shouldBe entity.name
        },
      onRequest(security = bearer.apply(validJwt(authContext)), pathParameters = __Aggregate__Id(UUID.randomUUID()))
        .respondsWith[Error[Unit]](NotFound, description = "__Aggregate__ not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("__Aggregate__ not found")
        }
    )
  )
}
