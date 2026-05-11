package madrileno.utils.task

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.*

final case class SchedulerConfig(
  concurrency: Int = 10,
  pollingInterval: Duration = 10.seconds,
  heartbeatInterval: Duration = 5.minutes,
  missedHeartbeatLimit: Int = 6,
  retryBaseDelay: Duration = 30.seconds,
  retryBackoffRate: Double = 1.5,
  retryMaxDelay: Duration = 1.hour,
  maxRetries: Option[Int] = None,
  schedulerName: Option[String] = None)

object SchedulerConfig {
  given ConfigReader[SchedulerConfig] = deriveReader[SchedulerConfig]
}
