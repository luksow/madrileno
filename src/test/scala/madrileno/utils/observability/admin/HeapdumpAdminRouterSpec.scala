package madrileno.utils.observability.admin

import madrileno.support.{BaseRouteSpec, TestApplicationLoader}
import madrileno.utils.http.Error
import org.http4s.Method.*
import org.http4s.Status.*
import org.http4s.circe.CirceEntityCodec.*
import pl.iterators.baklava.{HttpBasic, SecurityScheme}
import pl.iterators.stir.server.Route

import java.io.File
import java.nio.file.{Files, Paths}

class HeapdumpAdminRouterSpec extends BaseRouteSpec with TestApplicationLoader {
  override def route: Route = application.routes(wsb)

  private val basic       = HttpBasic()
  private val basicScheme = SecurityScheme("admin-basic", basic)
  private val adminUser   = "admin"
  private val adminPass   = "admin"

  private val testDumpPath = s"${System.getProperty("java.io.tmpdir")}/madrileno-heapdump-spec-${System.nanoTime()}.hprof"

  override def afterAll(): Unit = {
    val _ = Files.deleteIfExists(Paths.get(testDumpPath))
    super.afterAll()
  }

  path("/admin/heapdump")(
    supports(
      POST,
      description = "Triggers an HPROF heap dump via `HotSpotDiagnosticMXBean.dumpHeap(...)`. The dump is written to a server-side path " +
        "(not streamed in the response — heap dumps can be multi-GB). Returns the path, size, the `live` flag used, and how long the dump took. " +
        "Operator workflow: hit this endpoint, then `scp` the file off the host and open in Eclipse MAT / VisualVM / jhat.",
      summary = "Trigger an HPROF heap dump",
      securitySchemes = Seq(basicScheme),
      queryParameters = (
        q[Option[Boolean]]("live", "Only dump reachable objects (default: true). Set to false to include unreachable / not-yet-collected objects."),
        q[Option[String]]("path", "Override the server-side output path. Default: ${java.io.tmpdir}/madrileno-heap-<ts>-<pid>.hprof")
      ),
      tags = Seq("Admin")
    )(
      onRequest(security = basic.apply(adminUser, adminPass), queryParameters = (Some(true), Some(testDumpPath)))
        .respondsWith[HeapdumpResultDto](Ok, description = "Heap dump written to disk")
        .assert { ctx =>
          val body = ctx.performRequest(allRoutes).body
          body.path shouldBe testDumpPath
          body.liveOnly shouldBe true
          body.sizeBytes should be > 0L
          new File(testDumpPath).exists() shouldBe true
        },
      onRequest(queryParameters = (Some(true), Some(testDumpPath)))
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )
}
