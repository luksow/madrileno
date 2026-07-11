package madrileno.utils.events.outbox

import madrileno.utils.events.EventCodec

import java.util.UUID

final case class DomainEventDescriptor[A](
  eventType: String,
  aggregateType: String,
  aggregateId: A => UUID
)(using val codec: EventCodec[A])
