package madrileno.utils.observability.admin

import com.typesafe.config.ConfigFactory
import io.circe.Json
import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{FreeFormSchema, HttpBasic, Schema, SecurityScheme}
import pl.iterators.stir.server.Route

class ConfigAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private given Schema[Json] = FreeFormSchema("ConfigTree")

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  private def at(json: Json, path: String*): Option[Json] =
    path.foldLeft(Option(json))((acc, key) => acc.flatMap(_.asObject).flatMap(_(key)))

  describe("ConfigAdminRouter.redact") {
    it("redacts primitive elements of a list whose parent key matches the heuristic") {
      val cfg  = ConfigFactory.parseString("""tokens = ["alpha", "beta", "gamma"]""")
      val out  = ConfigAdminRouter.redact(cfg, declaredKeys = Set("tokens"), redactedPaths = Set.empty)
      val list = at(out, "tokens").flatMap(_.asArray).get
      list.flatMap(_.asString).toList shouldBe List("[REDACTED]", "[REDACTED]", "[REDACTED]")
    }

    it("walks into object elements of a secret-keyed list and only redacts matching keys inside") {
      val cfg  = ConfigFactory.parseString("""tokens = [{ name = "alpha", secret = "abc" }, { name = "beta", secret = "def" }]""")
      val out  = ConfigAdminRouter.redact(cfg, declaredKeys = Set("tokens"), redactedPaths = Set.empty)
      val list = at(out, "tokens").flatMap(_.asArray).get
      at(list(0), "name").flatMap(_.asString) shouldBe Some("alpha")
      at(list(0), "secret").flatMap(_.asString) shouldBe Some("[REDACTED]")
      at(list(1), "name").flatMap(_.asString) shouldBe Some("beta")
      at(list(1), "secret").flatMap(_.asString) shouldBe Some("[REDACTED]")
    }

    it("respects an explicit redacted-paths entry for a non-keyword key") {
      val cfg = ConfigFactory.parseString("""custom { boring-field = "shh" }""")
      val out = ConfigAdminRouter.redact(cfg, declaredKeys = Set("custom"), redactedPaths = Set("custom.boring-field"))
      at(out, "custom", "boring-field").flatMap(_.asString) shouldBe Some("[REDACTED]")
    }

    it("filters top-level keys to the declared allowlist (keeps JVM/system noise out)") {
      val cfg = ConfigFactory.parseString("""
        |declared { x = 1 }
        |sneaky { y = 2 }
        """.stripMargin)
      val out = ConfigAdminRouter.redact(cfg, declaredKeys = Set("declared"), redactedPaths = Set.empty)
      out.asObject.map(_.keys.toSet) shouldBe Some(Set("declared"))
    }

    it("shows JVM -D overrides through the merged Config (truth, not file-on-disk)") {
      val merged = ConfigFactory
        .parseString("""http { port = 9000 }""")
        .withFallback(ConfigFactory.parseString("http.port = 8080"))
        .resolve()
      val out = ConfigAdminRouter.redact(merged, declaredKeys = Set("http"), redactedPaths = Set.empty)
      at(out, "http", "port").flatMap(_.asNumber).flatMap(_.toInt) shouldBe Some(9000)
    }
  }

  path("/admin/config")(
    supports(
      GET,
      description = "Returns the merged application configuration (HOCON resolved against env vars) as a JSON tree. " +
        "Leaf values whose key name contains `password`, `secret`, `credential`, `access-key`, or `token` are replaced with `\"[REDACTED]\"`. " +
        "Project-specific secrets can be added via `admin.config.redacted-paths`.",
      summary = "Inspect merged configuration",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[Json](Ok, description = "Config tree with secrets redacted")
        .assert { ctx =>
          val body = ctx.performRequest(allRoutes).body
          // Non-secret values render verbatim
          at(body, "app", "name").flatMap(_.asString) shouldBe Some("madrileno")
          at(body, "http", "port").flatMap(_.asNumber).flatMap(_.toInt) shouldBe Some(9000)
          at(body, "pg", "database").flatMap(_.asString) shouldBe Some("madrileno")
          // Known-secret paths (with HOCON defaults, so they always render in tests) are redacted
          at(body, "jwt", "secret").flatMap(_.asString) shouldBe Some("[REDACTED]")
          at(body, "admin", "password").flatMap(_.asString) shouldBe Some("[REDACTED]")
          at(body, "storage", "object-storage", "access-key-id").flatMap(_.asString) shouldBe Some("[REDACTED]")
          at(body, "storage", "object-storage", "secret-access-key").flatMap(_.asString) shouldBe Some("[REDACTED]")
          // Non-secret keys near secret ones are NOT redacted (validates the leaf-only redaction)
          at(body, "jwt", "valid-for").flatMap(_.asString) shouldBe Some("PT5M")
          at(body, "admin", "user").flatMap(_.asString) shouldBe Some("admin")
          // Sub-objects under a "token-ish" key are walked, not redacted wholesale
          at(body, "refresh-token").flatMap(_.asObject).map(_.isEmpty) shouldBe Some(true) // valid-for unset by default
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )
}
