package madrileno.auth.routers

import cats.effect.IO
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.RateLimiterRuntime
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.typelevel.ci.CIString
import pl.iterators.stir.server.Route

class AuthRateLimitSpec extends BaseRouteSpec with TestApplicationLoader {

  override protected def rateLimiterRuntime: RateLimiterRuntime = RateLimiterRuntime.scaffeine()

  override def route: Route = application.routes(wsb)

  private def post(path: String, body: String) = {
    val request = Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
    allRoutes.orNotFound.run(request).unsafeRunSync()
  }

  describe("auth endpoint rate limiting") {
    it("returns 429 with Retry-After once the per-client limit for POST /auth/dev (10/min) is exceeded") {
      def postDev() = post("/v1/auth/dev", """{"email":"throttle@example.com"}""")
      (1 to 10).foreach(_ => postDev().status shouldBe Status.NotFound)
      val limited = postDev()
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("60")
    }

    it("returns 429 once the per-client limit for POST /auth/firebase (10/min) is exceeded") {
      def postFirebase() = post("/v1/auth/firebase", "{}")
      (1 to 10).foreach(_ => postFirebase().status should not be Status.TooManyRequests)
      postFirebase().status shouldBe Status.TooManyRequests
    }
  }
}
