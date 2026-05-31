package madrileno.utils.observability.admin

import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{FreeFormSchema, HttpBasic, Schema, SecurityScheme}
import pl.iterators.stir.server.Route

class ThreaddumpAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  private given Schema[ThreaddumpDto] = FreeFormSchema("Threaddump")

  path("/admin/threaddump")(
    supports(
      GET,
      description = "Returns a snapshot of the JVM thread state (Spring-Actuator shape) alongside the cats-effect live fiber snapshot. " +
        "Use this for 'what is this process stuck doing?' debugging. `jvmThreads` exposes non-fiber threads (Netty I/O, Skunk pool, scheduler, finalizer); " +
        "`fibers.workers` shows fibers queued on each compute worker, `fibers.external` shows suspended fibers (Deferred.get, Sleep, etc.). " +
        "Fiber traces depend on `-Dcats.effect.tracing.mode` — default `cached` populates them with the captured async-boundary frames.",
      summary = "Inspect JVM threads + cats-effect fibers",
      securitySchemes = Seq(basicScheme),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass))
        .respondsWith[ThreaddumpDto](Ok, description = "JVM threads + cats-effect fiber snapshot")
        .assert { ctx =>
          val body = ctx.performRequest(allRoutes).body
          // The JVM always has at least the test runner thread plus internal threads
          body.jvmThreads should not be empty
          // The cats-effect work-stealing pool exposes one worker per logical core
          body.fibers.workers should not be empty
          // Stack traces are populated (worker threads are parked, so they have at least a `park`/`run` frame)
          body.jvmThreads.flatMap(_.stackTrace) should not be empty
        },
      onRequest()
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )
}
