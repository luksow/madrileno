package madrileno.utils.observability.admin

import madrileno.utils.json.JsonProtocol.*

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

final case class StackFrameDto(
  className: String,
  methodName: String,
  fileName: Option[String],
  lineNumber: Int,
  nativeMethod: Boolean)
    derives Encoder.AsObject,
      Decoder

final case class LockInfoDto(className: String, identityHashCode: Int) derives Encoder.AsObject, Decoder

final case class MonitorInfoDto(
  className: String,
  identityHashCode: Int,
  lockedStackDepth: Int,
  lockedStackFrame: Option[StackFrameDto])
    derives Encoder.AsObject,
      Decoder

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
