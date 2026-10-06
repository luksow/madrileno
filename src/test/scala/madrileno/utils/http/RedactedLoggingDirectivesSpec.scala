package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.Fingerprint
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Response, Status}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.stir.server.{Directives, Route, ToHttpRoutes}

class RedactedLoggingDirectivesSpec extends AnyFunSpec with Matchers with Directives with RedactedLoggingDirectives {

  private val refreshToken = "0192f5d4-2b3a-7c1e-9a0b-6f1e2d3c4b5a"
  private val jwt          = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"

  private val echoJson: Route = post {
    entity(as[Json]) { json =>
      complete(Status.Ok -> json)
    }
  }

  private val ignoreBody: Route = complete(Status.NoContent)

  private val drainBody: Route = extractRequest { request =>
    onSuccess(request.body.compile.drain) {
      complete(Status.NoContent)
    }
  }

  /** Runs the route under both logging directives, draining the response body (that is what triggers the response log line). */
  private def run(
    route: Route,
    request: Request[IO],
    maxBodyBytes: Int = RedactedLoggingDirectives.DefaultMaxBodyBytes
  ): (Response[IO], String, List[String]) = {
    (for {
      lines    <- IO.ref(List.empty[String])
      log       = (line: String) => lines.update(_ :+ line)
      routes    = logRedactedRequest(log, maxBodyBytes)(logRedactedResult(log, maxBodyBytes)(route)).toHttpRoutes
      response <- routes.orNotFound.run(request)
      body     <- response.bodyText.compile.string
      logged   <- lines.get
    } yield (response, body, logged)).unsafeRunSync()
  }

  describe("logRedactedRequest / logRedactedResult") {
    it("logs JSON bodies with sensitive fields replaced by fingerprints") {
      val payload = Json.obj("refreshToken" -> Json.fromString(refreshToken), "jwt" -> Json.fromString(jwt), "userCreated" -> Json.True)

      val (response, body, logged) = run(echoJson, Request[IO](Method.POST, uri"/").withEntity(payload))

      response.status shouldBe Status.Ok
      body should include(refreshToken)
      logged should have size 2
      logged.foreach { line =>
        line should not include refreshToken
        line should not include jwt
        line should include(Fingerprint(refreshToken).value)
        line should include("\"userCreated\":true")
      }
      logged.head should startWith("POST / HTTP/1.1 Headers(")
      logged(1) should startWith("HTTP/1.1 200 OK Headers(")
    }

    it("logs non-JSON bodies as content type and size only") {
      val (_, _, logged) = run(drainBody, Request[IO](Method.POST, uri"/").withEntity("hello"))

      logged.head should include("<text/plain, 5 bytes>")
      logged.head should not include "hello"
    }

    it("logs malformed JSON as its size only") {
      val request = Request[IO](Method.POST, uri"/").withEntity("{oops").withContentType(`Content-Type`(MediaType.application.json))

      val (response, _, logged) = run(echoJson, request)

      // The decoder rejects; without a rejection handler `toHttpRoutes` turns that into "no route", hence 404 rather than 400.
      response.status shouldBe Status.NotFound
      logged.exists(_.contains("<malformed json, 5 bytes>")) shouldBe true
      logged.foreach(_ should not include "oops")
    }

    it("logs a JSON body over the size limit without content") {
      val payload = Json.obj("refreshToken" -> Json.fromString(refreshToken), "padding" -> Json.fromString("x".repeat(100)))

      val (_, _, logged) = run(echoJson, Request[IO](Method.POST, uri"/").withEntity(payload), maxBodyBytes = 64)

      logged should have size 2
      logged.foreach { line =>
        line should include("over the 64-byte log limit")
        line should not include refreshToken
      }
    }

    it("notes when the route never read the request body") {
      val (_, _, logged) = run(ignoreBody, Request[IO](Method.POST, uri"/").withEntity(Json.obj("jwt" -> Json.fromString(jwt))))

      logged.head should include("<not consumed, ")
      logged.head should not include jwt
    }

    it("redacts sensitive headers") {
      val request = Request[IO](Method.GET, uri"/").putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, jwt)))

      val (_, _, logged) = run(ignoreBody, request)

      logged.head should include("Authorization: <REDACTED>")
      logged.head should not include jwt
    }

    it("logs rejections") {
      val (response, _, logged) = run(reject, Request[IO](Method.GET, uri"/"))

      response.status shouldBe Status.NotFound
      logged(1) should startWith("Request was rejected")
    }
  }
}
