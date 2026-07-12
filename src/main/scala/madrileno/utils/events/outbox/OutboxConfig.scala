package madrileno.utils.events.outbox

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.*

final case class OutboxConfig(
  recoveryInterval: FiniteDuration = 5.minutes,
  recoveryBatchSize: Int = 100,
  deliveryMaxRetries: Option[Int] = Some(10))

object OutboxConfig {
  given ConfigReader[OutboxConfig] = deriveReader[OutboxConfig]
}
