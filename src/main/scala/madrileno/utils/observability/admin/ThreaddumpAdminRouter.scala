package madrileno.utils.observability.admin

import cats.effect.unsafe.IORuntime
import cats.effect.{FiberInfo, IO}
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.lang.management.{LockInfo, ManagementFactory, MonitorInfo, ThreadInfo}

class ThreaddumpAdminRouter(runtime: IORuntime) extends BaseRouter {

  val routes: Route =
    (get & pathPrefix("threaddump") & pathEndOrSingleSlash) {
      complete(IO.delay(dump()).map[ToResponseMarshallable](Ok -> _))
    }

  private def dump(): ThreaddumpDto = {
    val mx         = ManagementFactory.getThreadMXBean
    val infos      = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported, mx.isSynchronizerUsageSupported)
    val snapshot   = runtime.liveFiberSnapshot()
    val jvmThreads = infos.toList.map(toJvmThreadDto).sortBy(_.threadName)
    val workers = snapshot.workers.toList
      .map { case (worker, fibers) =>
        WorkerFibersDto(worker.thread.getName, worker.index, fibers.map(ThreaddumpAdminRouter.toFiberDto))
      }
      .sortBy(_.workerIndex)
    val external = snapshot.external.map(ThreaddumpAdminRouter.toFiberDto)
    ThreaddumpDto(jvmThreads, FiberDumpDto(workers, external))
  }

  private def toJvmThreadDto(info: ThreadInfo): JvmThreadDto =
    JvmThreadDto(
      threadName = info.getThreadName,
      threadId = info.getThreadId,
      threadState = info.getThreadState.name,
      priority = info.getPriority,
      daemon = info.isDaemon,
      suspended = info.isSuspended,
      inNative = info.isInNative,
      blockedCount = info.getBlockedCount,
      blockedTime = info.getBlockedTime,
      waitedCount = info.getWaitedCount,
      waitedTime = info.getWaitedTime,
      lockName = Option(info.getLockName),
      lockOwnerId = info.getLockOwnerId,
      lockOwnerName = Option(info.getLockOwnerName),
      stackTrace = info.getStackTrace.toList.map(ThreaddumpAdminRouter.toStackFrame),
      lockedMonitors = info.getLockedMonitors.toList.map(ThreaddumpAdminRouter.toMonitorInfo),
      lockedSynchronizers = info.getLockedSynchronizers.toList.map(ThreaddumpAdminRouter.toLockInfo)
    )
}

object ThreaddumpAdminRouter {

  private[admin] def toStackFrame(ste: StackTraceElement): StackFrameDto =
    StackFrameDto(
      className = ste.getClassName,
      methodName = ste.getMethodName,
      fileName = Option(ste.getFileName),
      lineNumber = ste.getLineNumber,
      nativeMethod = ste.isNativeMethod
    )

  private[admin] def toLockInfo(lock: LockInfo): LockInfoDto =
    LockInfoDto(className = lock.getClassName, identityHashCode = lock.getIdentityHashCode)

  private[admin] def toMonitorInfo(m: MonitorInfo): MonitorInfoDto =
    MonitorInfoDto(
      className = m.getClassName,
      identityHashCode = m.getIdentityHashCode,
      lockedStackDepth = m.getLockedStackDepth,
      lockedStackFrame = Option(m.getLockedStackFrame).map(toStackFrame)
    )

  private[admin] def toFiberDto(info: FiberInfo): FiberInfoDto =
    FiberInfoDto(id = System.identityHashCode(info.fiber).toHexString, state = info.state.toString, trace = info.trace.toList.map(frameToString))

  private def frameToString(ste: StackTraceElement): String =
    s"${ste.getClassName}.${ste.getMethodName} (${ste.getFileName}:${ste.getLineNumber})"
}
