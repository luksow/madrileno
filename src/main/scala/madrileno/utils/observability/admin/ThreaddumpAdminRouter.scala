package madrileno.utils.observability.admin

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.lang.management.ManagementFactory

class ThreaddumpAdminRouter(runtime: IORuntime) extends BaseRouter {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash) {
      complete(IO.delay(dump()).map[ToResponseMarshallable](Ok -> _))
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
