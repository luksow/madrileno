package madrileno.utils.observability.admin

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
