package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import madrileno.utils.observability.TelemetryContext
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import pl.iterators.stir.server.{Route, ToHttpRoutes}

import scala.concurrent.duration.DurationInt

class RateLimitDirectivesSpec extends AnyFunSpec with Matchers {

  private given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())
  private given IORuntime        = IORuntime.global

  private def routesFor(rl: RateLimiter): Route = {
    val router = new BaseRouter with RateLimitDirectives {
      override protected val rateLimiter: RateLimiter = rl
    }
    import router.*
    rateLimited("test", RateLimit(to = 3, within = 1.minute)) {
      (get & path("hello") & pathEndOrSingleSlash) {
        complete(router.Ok -> "ok")
      }
    }
  }

  private def hit(routes: Route, header: Option[(String, String)] = None) = {
    val base    = Request[IO](Method.GET, Uri.unsafeFromString("/hello"))
    val request = header.fold(base) { case (n, v) => base.putHeaders(org.http4s.Header.Raw(CIString(n), v)) }
    routes.toHttpRoutes.orNotFound.run(request).unsafeRunSync()
  }

  describe("rateLimited directive") {
    it("passes through under the limit and returns 429 once exceeded") {
      val routes = routesFor(RateLimiterRuntime.caffeine().rateLimiter)
      hit(routes).status shouldBe Status.Ok
      hit(routes).status shouldBe Status.Ok
      hit(routes).status shouldBe Status.Ok
      val limited = hit(routes)
      limited.status shouldBe Status.TooManyRequests
      limited.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("60")
    }

    it("isolates buckets by discriminator key") {
      val rl = RateLimiterRuntime.caffeine().rateLimiter
      val withKey: String => Route = clientId => {
        val router = new BaseRouter with RateLimitDirectives {
          override protected val rateLimiter: RateLimiter = rl
        }
        import router.*
        rateLimited("isolated", RateLimit(to = 1, within = 1.minute), by = byHeader("X-Client")) {
          (get & path("hello") & pathEndOrSingleSlash) { complete(router.Ok -> clientId) }
        }
      }

      hit(withKey("a"), Some("X-Client" -> "a")).status shouldBe Status.Ok
      hit(withKey("a"), Some("X-Client" -> "a")).status shouldBe Status.TooManyRequests
      hit(withKey("b"), Some("X-Client" -> "b")).status shouldBe Status.Ok
    }

    it("does not increment the counter for branches that don't match the method/path") {
      val rl = RateLimiterRuntime.caffeine().rateLimiter
      val routes: Route = {
        val router = new BaseRouter with RateLimitDirectives {
          override protected val rateLimiter: RateLimiter = rl
        }
        import router.*
        (get & path("hello") & pathEndOrSingleSlash & rateLimited("get-bucket", RateLimit(to = 1, within = 1.minute))) {
          complete(router.Ok -> "got")
        } ~
          (post & path("hello") & pathEndOrSingleSlash & rateLimited("post-bucket", RateLimit(to = 1, within = 1.minute))) {
            complete(router.Created -> "posted")
          }
      }

      (1 to 100).foreach { _ =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString("/hello"))
        routes.toHttpRoutes.orNotFound.run(req).unsafeRunSync()
      }
      val getResp = routes.toHttpRoutes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString("/hello"))).unsafeRunSync()
      getResp.status shouldBe Status.Ok
    }

    it("byClientIp prefers X-Forwarded-For over the socket address") {
      val rl = RateLimiterRuntime.caffeine().rateLimiter
      val routes: Route = {
        val router = new BaseRouter with RateLimitDirectives {
          override protected val rateLimiter: RateLimiter = rl
        }
        import router.*
        (get & path("hello") & pathEndOrSingleSlash & rateLimited("xff", RateLimit(to = 1, within = 1.minute), by = byClientIp)) {
          complete(router.Ok -> "ok")
        }
      }

      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "5.6.7.8")).status shouldBe Status.Ok
      hit(routes, Some("X-Forwarded-For" -> "1.2.3.4")).status shouldBe Status.TooManyRequests
    }
  }
}
