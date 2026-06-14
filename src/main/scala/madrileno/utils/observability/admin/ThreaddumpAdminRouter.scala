package madrileno.utils.observability.admin

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

class ThreaddumpAdminRouter(runtime: IORuntime, override protected val rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash & rateLimited("admin.threaddump", to = 20, within = 1.minute)) {
      complete(IO.blocking(dump()).map[ToResponseMarshallable](Ok -> _))
    }

  private def dump(): ThreaddumpDto = {
    val mx         = ManagementFactory.getThreadMXBean
    val infos      = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported, mx.isSynchronizerUsageSupported)
    val snapshot   = runtime.liveFiberSnapshot()
    val jvmThreads = infos.toList.map(JvmThreadDto.apply).sortBy(_.threadName)
    val workers = snapshot.workers.toList
      .map { case (worker, fibers) =>
        WorkerFibersDto(worker.thread.getName, worker.index, fibers.map(FiberInfoDto.apply))
      }
      .sortBy(_.workerIndex)
    val external = snapshot.external.map(FiberInfoDto.apply)
    ThreaddumpDto(jvmThreads, FiberDumpDto(workers, external))
  }
}
