package madrileno.utils.outbox

import io.circe.Json
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type DomainEventId = UUID
object DomainEventId extends Opaque[DomainEventId, UUID]

final case class DomainEvent(
  id: DomainEventId,
  eventType: String,
  aggregateType: String,
  aggregateId: UUID,
  payload: Json,
  occurredAt: Instant)
