package madrileno.utils.observability.admin

import cats.effect.unsafe.IORuntime
import cats.effect.{FiberSnapshot, IO}
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import pl.iterators.stir.server.Route

import java.lang.management.ManagementFactory
import scala.concurrent.duration.*

class ThreaddumpAdminRouter(runtime: IORuntime, override protected val rateLimiterRuntime: RateLimiterRuntime)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash & rateLimited("admin.threaddump", to = 20, within = 1.minute)) {
      complete {
        ThreaddumpAdminRouter
          .retryingOnHandoff(IO.blocking(runtime.liveFiberSnapshot()))
          .flatMap(snapshot => IO.blocking(dump(snapshot)))
          .map(Ok -> _)
      }
    }

  private def dump(snapshot: FiberSnapshot): ThreaddumpDto = {
    val mx         = ManagementFactory.getThreadMXBean
    val infos      = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported, mx.isSynchronizerUsageSupported)
    val jvmThreads = infos.toList.map(JvmThreadDto.apply).sortBy(_.threadName)
    val workers    = snapshot.workers.toList
      .map { case (worker, fibers) =>
        WorkerFibersDto(worker.thread.getName, worker.index, fibers.map(FiberInfoDto.apply))
      }
      .sortBy(_.workerIndex)
    val external = snapshot.external.map(FiberInfoDto.apply)
    ThreaddumpDto(jvmThreads, FiberDumpDto(workers, external))
  }
}

object ThreaddumpAdminRouter {
  val SnapshotAttempts: Int              = 5
  val SnapshotRetryPause: FiniteDuration = 10.millis

  def retryingOnHandoff(
    take: IO[FiberSnapshot],
    attempts: Int = SnapshotAttempts,
    pause: FiniteDuration = SnapshotRetryPause
  ): IO[FiberSnapshot] =
    take.recoverWith {
      case _: NullPointerException if attempts > 1 => IO.sleep(pause) *> retryingOnHandoff(take, attempts - 1, pause)
    }
}
