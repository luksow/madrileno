package madrileno.utils.observability.admin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Method, Request, Status, Uri}
import pl.iterators.stir.server.Route

class LoggersAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val loggersUri                   = Uri.unsafeFromString("/admin/loggers")
  private def loggerUri(name: String): Uri = Uri.unsafeFromString(s"/admin/loggers/$name")
  private val adminAuth                    = Authorization(BasicCredentials("admin", "admin"))

  describe("/admin/loggers") {
    it("returns a list of configured loggers when authenticated") {
      val request  = Request[IO](method = Method.GET, uri = loggersUri, headers = Headers(adminAuth))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Ok
      val body = response.as[List[LoggerLevelDto]].unsafeRunSync()
      body should not be empty
      body.map(_.name) should contain("ROOT")
    }

    it("rejects requests without credentials with 401") {
      val request  = Request[IO](method = Method.GET, uri = loggersUri)
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Unauthorized
    }
  }

  describe("/admin/loggers/{name}") {
    it("returns the level for the ROOT logger") {
      val request  = Request[IO](method = Method.GET, uri = loggerUri("ROOT"), headers = Headers(adminAuth))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.Ok
      val dto = response.as[LoggerLevelDto].unsafeRunSync()
      dto.name shouldBe "ROOT"
      dto.configuredLevel shouldBe defined
      dto.effectiveLevel should not be empty
    }

    it("sets the level for a named logger and a subsequent GET reflects it") {
      val testLoggerName = "madrileno.test.LoggersAdminRouterSpec"
      val setRequest = Request[IO](method = Method.POST, uri = loggerUri(testLoggerName), headers = Headers(adminAuth))
        .withEntity(SetLoggerLevelRequest(level = Some("DEBUG")).asJson)
      val setResponse = allRoutes.orNotFound.run(setRequest).unsafeRunSync()
      setResponse.status shouldBe Status.Ok
      val setDto = setResponse.as[LoggerLevelDto].unsafeRunSync()
      setDto.configuredLevel shouldBe Some("DEBUG")
      setDto.effectiveLevel shouldBe "DEBUG"

      val getRequest  = Request[IO](method = Method.GET, uri = loggerUri(testLoggerName), headers = Headers(adminAuth))
      val getResponse = allRoutes.orNotFound.run(getRequest).unsafeRunSync()
      getResponse.status shouldBe Status.Ok
      getResponse.as[LoggerLevelDto].unsafeRunSync().configuredLevel shouldBe Some("DEBUG")
    }

    it("clears the configured level when level is null and falls back to inherited") {
      val testLoggerName = "madrileno.test.InheritTest"
      val set = Request[IO](method = Method.POST, uri = loggerUri(testLoggerName), headers = Headers(adminAuth))
        .withEntity(SetLoggerLevelRequest(level = Some("WARN")).asJson)
      allRoutes.orNotFound.run(set).unsafeRunSync().status shouldBe Status.Ok

      val clear = Request[IO](method = Method.POST, uri = loggerUri(testLoggerName), headers = Headers(adminAuth))
        .withEntity(Json.obj("level" -> Json.Null))
      val clearResponse = allRoutes.orNotFound.run(clear).unsafeRunSync()
      clearResponse.status shouldBe Status.Ok
      val dto = clearResponse.as[LoggerLevelDto].unsafeRunSync()
      dto.configuredLevel shouldBe None
      dto.effectiveLevel should not be empty
    }

    it("rejects an unknown level with 400") {
      val request = Request[IO](method = Method.POST, uri = loggerUri("madrileno.test.Unknown"), headers = Headers(adminAuth))
        .withEntity(SetLoggerLevelRequest(level = Some("VERBOSE")).asJson)
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.BadRequest
    }

    it("returns 404 for an unknown logger (no configuration anywhere)") {
      val request  = Request[IO](method = Method.GET, uri = loggerUri("madrileno.test.NeverConfigured"), headers = Headers(adminAuth))
      val response = allRoutes.orNotFound.run(request).unsafeRunSync()
      response.status shouldBe Status.NotFound
    }
  }
}
