package madrileno.utils.http

import cats.effect.unsafe.IORuntime
import org.http4s.Status
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.iterators.stir.server.Directives.*
import pl.iterators.stir.server.Route
import pl.iterators.stir.testkit.ScalatestRouteTest

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

class ApiVersionDirectivesSpec extends AnyFunSpec with Matchers with ScalatestRouteTest {

  override implicit def runtime: IORuntime = IORuntime.global

  import ApiVersionDirectives.*

  describe("apiVersionPrefix") {
    val route: Route =
      apiVersionPrefix { matched =>
        path("ping")(complete(s"matched=${matched.urlSegment}"))
      }

    it("matches and extracts a known ApiVersion") {
      Get("/v1/ping") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "matched=v1"
      }
    }

    it("rejects unknown version segments") {
      Get("/vUNKNOWN/ping") ~> route ~> check {
        handled shouldBe false
      }
    }
  }

  describe("apiVersion") {
    it("passes when the current matched version equals the target") {
      val route: Route =
        apiVersionPrefix { matched =>
          given ApiVersion = matched
          path("ping") {
            apiVersion(ApiVersion.V1)(complete("v1-branch"))
          }
        }

      Get("/v1/ping") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "v1-branch"
      }
    }

    // When V2 is added to the ApiVersion enum, add a test here verifying
    // apiVersion(V2) rejects under (using V1 = current) so the alternative branch handles it.
  }

  describe("deprecated") {
    val sunset = Instant.parse("2026-11-11T23:59:59Z")
    val route: Route =
      deprecated(sunset) {
        complete("ok")
      }

    it("adds the Deprecation: true header") {
      Get("/") ~> route ~> check {
        header("Deprecation").map(_.value) shouldBe Some("true")
      }
    }

    it("adds the Sunset header as an HTTP-date") {
      Get("/") ~> route ~> check {
        val expected = DateTimeFormatter.RFC_1123_DATE_TIME.format(sunset.atOffset(ZoneOffset.UTC))
        header("Sunset").map(_.value) shouldBe Some(expected)
      }
    }

    it("does not interfere with the response body") {
      Get("/") ~> route ~> check {
        status shouldBe Status.Ok
        responseAs[String] shouldBe "ok"
      }
    }
  }
}
