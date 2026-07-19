package madrileno.utils.events.outbox

import madrileno.utils.events.EventCodec
import pl.iterators.kebs.core.macros.ValueClassLike

import java.util.UUID

final case class DomainEventDescriptor[A] private (
  eventType: String,
  aggregateType: String,
  aggregateId: A => UUID
)(using val codec: EventCodec[A])

object DomainEventDescriptor {
  def apply[A, I](
    eventType: String,
    aggregateType: String,
    aggregateId: A => I
  )(using
    vcl: ValueClassLike[I, UUID],
    codec: EventCodec[A]
  ): DomainEventDescriptor[A] =
    new DomainEventDescriptor(eventType, aggregateType, a => vcl.unapply(aggregateId(a)))
}
