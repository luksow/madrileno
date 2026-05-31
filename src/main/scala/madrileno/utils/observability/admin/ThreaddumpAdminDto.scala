package madrileno.utils.observability.admin

import cats.effect.FiberInfo
import madrileno.utils.json.JsonProtocol.*

import java.lang.management.{LockInfo, MonitorInfo, ThreadInfo}

final case class ThreaddumpDto(jvmThreads: List[JvmThreadDto], fibers: FiberDumpDto) derives Encoder.AsObject, Decoder

final case class JvmThreadDto(
  threadName: String,
  threadId: Long,
  threadState: String,
  priority: Int,
  daemon: Boolean,
  suspended: Boolean,
  inNative: Boolean,
  blockedCount: Long,
  blockedTime: Long,
  waitedCount: Long,
  waitedTime: Long,
  lockName: Option[String],
  lockOwnerId: Long,
  lockOwnerName: Option[String],
  stackTrace: List[StackFrameDto],
  lockedMonitors: List[MonitorInfoDto],
  lockedSynchronizers: List[LockInfoDto])
    derives Encoder.AsObject,
      Decoder

object JvmThreadDto {
  def apply(info: ThreadInfo): JvmThreadDto =
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
      stackTrace = info.getStackTrace.toList.map(StackFrameDto.apply),
      lockedMonitors = info.getLockedMonitors.toList.map(MonitorInfoDto.apply),
      lockedSynchronizers = info.getLockedSynchronizers.toList.map(LockInfoDto.apply)
    )
}

final case class StackFrameDto(
  className: String,
  methodName: String,
  fileName: Option[String],
  lineNumber: Int,
  nativeMethod: Boolean)
    derives Encoder.AsObject,
      Decoder

object StackFrameDto {
  def apply(ste: StackTraceElement): StackFrameDto =
    StackFrameDto(
      className = ste.getClassName,
      methodName = ste.getMethodName,
      fileName = Option(ste.getFileName),
      lineNumber = ste.getLineNumber,
      nativeMethod = ste.isNativeMethod
    )
}

final case class LockInfoDto(className: String, identityHashCode: Int) derives Encoder.AsObject, Decoder

object LockInfoDto {
  def apply(lock: LockInfo): LockInfoDto =
    LockInfoDto(className = lock.getClassName, identityHashCode = lock.getIdentityHashCode)
}

final case class MonitorInfoDto(
  className: String,
  identityHashCode: Int,
  lockedStackDepth: Int,
  lockedStackFrame: Option[StackFrameDto])
    derives Encoder.AsObject,
      Decoder

object MonitorInfoDto {
  def apply(m: MonitorInfo): MonitorInfoDto =
    MonitorInfoDto(
      className = m.getClassName,
      identityHashCode = m.getIdentityHashCode,
      lockedStackDepth = m.getLockedStackDepth,
      lockedStackFrame = Option(m.getLockedStackFrame).map(StackFrameDto.apply)
    )
}

final case class FiberDumpDto(workers: List[WorkerFibersDto], external: List[FiberInfoDto]) derives Encoder.AsObject, Decoder

final case class WorkerFibersDto(
  workerThread: String,
  workerIndex: Int,
  fibers: List[FiberInfoDto])
    derives Encoder.AsObject,
      Decoder

final case class FiberInfoDto(
  id: String,
  state: String,
  trace: List[String])
    derives Encoder.AsObject,
      Decoder

object FiberInfoDto {
  def apply(info: FiberInfo): FiberInfoDto =
    FiberInfoDto(id = System.identityHashCode(info.fiber).toHexString, state = info.state.toString, trace = info.trace.toList.map(frameToString))

  private def frameToString(ste: StackTraceElement): String = {
    val source =
      if (ste.isNativeMethod) "Native Method"
      else
        Option(ste.getFileName).fold("Unknown Source") { f =>
          if (ste.getLineNumber >= 0) s"$f:${ste.getLineNumber}" else f
        }
    s"${ste.getClassName}.${ste.getMethodName} ($source)"
  }
}
