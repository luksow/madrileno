package madrileno.utils.observability.admin

import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{HttpBasic, SecurityScheme}
import pl.iterators.stir.server.Route

class DbAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  path("/admin/db")(
    supports(
      GET,
      description = "Returns Postgres pool config + connection-state breakdown for the current database. " +
        "Counts come from `pg_stat_activity` (server-side perspective) — in a multi-instance deployment, " +
        "`connections.total` reflects all clients on the database, not just this app instance.",
      summary = "Inspect Postgres connection-pool config + connection breakdown",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[DbStatusDto](Ok, description = "Postgres config + connection breakdown")
        .assert { ctx =>
          val body = ctx.performRequest(allRoutes).body
          body.config.host should not be empty
          body.config.port should be > 0
          body.config.database should not be empty
          body.config.maxPoolSize should be > 0
          // The test itself holds at least one connection while this assertion runs
          body.connections.total should be >= 1L
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )
}
