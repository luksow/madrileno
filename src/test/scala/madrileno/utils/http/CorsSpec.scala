package madrileno.utils.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.implicits.*
import org.http4s.{Header, HttpApp, Method, Request, Response, Status}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.ci.*

import java.net.URI
import scala.concurrent.duration.*

class CorsSpec extends AnyWordSpec with Matchers {

  private val ok: HttpApp[IO] = HttpApp.pure(Response[IO](Status.Ok))

  private def allowOriginHeader(
    config: CorsConfig,
    environment: String,
    baseUrl: URI,
    origin: String
  ): Option[String] = {
    val app = Cors.policy(config, environment, baseUrl).unsafeRunSync().fold(ok)(_(ok))
    val request = Request[IO](Method.OPTIONS, uri"/v1/auctions")
      .putHeaders(Header.Raw(ci"Origin", origin), Header.Raw(ci"Access-Control-Request-Method", "GET"))
    val response = app.run(request).unsafeRunSync()
    response.headers.get(ci"Access-Control-Allow-Origin").map(_.head.value)
  }

  private val localBaseUrl = URI.create("http://localhost:9000")

  "Cors.policy" should {
    "allow any origin in dev when allowed-origins is empty" in {
      allowOriginHeader(CorsConfig(enabled = true, allowedOrigins = "", maxAge = 1.hour), "dev", localBaseUrl, "http://localhost:5173") shouldBe Some(
        "*"
      )
    }

    "fall back to the base-url host outside dev" in {
      val prodBaseUrl = URI.create("https://api.example.com")
      allowOriginHeader(
        CorsConfig(enabled = true, allowedOrigins = "", maxAge = 1.hour),
        "production",
        prodBaseUrl,
        "https://api.example.com"
      ) shouldBe
        Some("https://api.example.com")
      allowOriginHeader(
        CorsConfig(enabled = true, allowedOrigins = "", maxAge = 1.hour),
        "production",
        prodBaseUrl,
        "https://evil.example.com"
      ) shouldBe None
    }

    "honor an explicit comma-separated allow-list" in {
      val config = CorsConfig(enabled = true, allowedOrigins = "https://app.example.com, https://www.example.com", maxAge = 1.hour)
      allowOriginHeader(config, "production", localBaseUrl, "https://app.example.com") shouldBe Some("https://app.example.com")
      allowOriginHeader(config, "production", localBaseUrl, "https://www.example.com") shouldBe Some("https://www.example.com")
      allowOriginHeader(config, "production", localBaseUrl, "https://other.example.com") shouldBe None
    }

    "treat \"*\" as allow-all even outside dev" in {
      allowOriginHeader(
        CorsConfig(enabled = true, allowedOrigins = "*", maxAge = 1.hour),
        "production",
        localBaseUrl,
        "https://anything.example.com"
      ) shouldBe
        Some("*")
    }

    "emit no CORS headers when disabled" in {
      Cors.policy(CorsConfig(enabled = false, allowedOrigins = "*", maxAge = 1.hour), "dev", localBaseUrl).unsafeRunSync() shouldBe None
      allowOriginHeader(
        CorsConfig(enabled = false, allowedOrigins = "*", maxAge = 1.hour),
        "dev",
        localBaseUrl,
        "http://localhost:5173"
      ) shouldBe None
    }
  }
}
