package madrileno.utils.observability.admin

import cats.effect.IO
import ch.qos.logback.classic.LoggerContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Method, Request, Status, Uri}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues.*
import org.slf4j.LoggerFactory
import pl.iterators.baklava.{EmptyBody, HttpBasic, SecurityScheme}
import pl.iterators.stir.server.Route

class LoggersAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader with BeforeAndAfterEach {
  override def route: Route = application.routes(wsb)

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  // Force-register a logger so the "set level on existing logger" cases find it via `exists`.
  private val testLoggerName = classOf[LoggersAdminRouterSpec].getName
  LoggerFactory.getLogger(testLoggerName)

  override def afterEach(): Unit = {
    LoggerFactory.getILoggerFactory match {
      case ctx: LoggerContext =>
        val logger = ctx.exists(testLoggerName)
        if (logger != null) logger.setLevel(null) // scalafix:ok DisableSyntax.null
      case _ => ()
    }
    super.afterEach()
  }

  path("/admin/loggers")(
    supports(
      GET,
      description = "List all configured loggers with their effective and configured levels.",
      summary = "Inspect Logback loggers",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[List[LoggerLevelDto]](Ok, description = "All registered loggers")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body should not be empty
          response.body.map(_.name) should contain("ROOT")
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/admin/loggers/{name}")(
    supports(
      GET,
      description = "Get one logger's configured + effective level. 404 if Logback has never been told about the name.",
      summary = "Inspect one Logback logger",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("name"),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "ROOT")
        .respondsWith[LoggerLevelDto](Ok, description = "Logger exists")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name shouldBe "ROOT"
          response.body.configuredLevel shouldBe defined
          response.body.effectiveLevel should not be empty
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = "madrileno.test.NeverConfigured")
        .respondsWith[Error[Unit]](NotFound, description = "Logger doesn't exist")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("No logger named")
        }
    ),
    supports(
      POST,
      description =
        "Set a logger's configured level. Send `{\"level\":\"DEBUG\"}` to set; `{\"level\":null}` (or `{}`) to clear and inherit from parent. " +
          "404 if the logger doesn't exist — Logback only knows about a logger after something has named it.",
      summary = "Set a Logback logger's level",
      securitySchemes = Seq(basicScheme),
      pathParameters = p[String]("name"),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = testLoggerName, body = SetLoggerLevelRequest(Some("DEBUG")))
        .respondsWith[LoggerLevelDto](Ok, description = "Level set")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.configuredLevel shouldBe Some("DEBUG")
          response.body.effectiveLevel shouldBe "DEBUG"
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = testLoggerName, body = SetLoggerLevelRequest(None))
        .respondsWith[LoggerLevelDto](Ok, description = "Override cleared; inherit from parent")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.configuredLevel shouldBe None
          response.body.effectiveLevel should not be empty
        },
      onRequest(security = basic.apply(adminUser, adminPass), pathParameters = testLoggerName, body = SetLoggerLevelRequest(Some("VERBOSE")))
        .respondsWith[Error[Unit]](BadRequest, description = "Unknown log level")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("unknown log level")
        },
      onRequest(
        security = basic.apply(adminUser, adminPass),
        pathParameters = "madrileno.test.NeverEverConfigured",
        body = SetLoggerLevelRequest(Some("DEBUG"))
      )
        .respondsWith[Error[Unit]](NotFound, description = "Logger doesn't exist")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title.value should include("No logger named")
        },
      onRequest(pathParameters = "ROOT", body = SetLoggerLevelRequest(Some("DEBUG")))
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  // Path-matching regression for the `path → pathPrefix` fix — Baklava's DSL declares paths
  // canonically without an explicit trailing slash, so this case stays as a raw http4s request.
  describe("trailing-slash handling") {
    it("accepts /admin/loggers/ with a trailing slash") {
      val request = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString("/admin/loggers/"),
        headers = Headers(Authorization(BasicCredentials(adminUser, adminPass)))
      )
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Ok
    }
  }
}
