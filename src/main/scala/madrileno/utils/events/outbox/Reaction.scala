package madrileno.utils.events.outbox

import java.time.Instant
import scala.util.control.NoStackTrace

enum Reaction {
  case Done
  case Retry(reason: String)
  case Drop(reason: String)
}

final case class Delivery(
  eventId: DomainEventId,
  consumer: String,
  attempt: Int,
  occurredAt: Instant)

final case class OutboxRetryRequested(reason: String) extends RuntimeException(reason) with NoStackTrace
