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
  trait AggregateId[I] {
    def toUuid(id: I): UUID
  }

  object AggregateId {
    given AggregateId[UUID] = id => id

    given [I](using vcl: ValueClassLike[I, UUID]): AggregateId[I] = id => vcl.unapply(id)
  }

  def apply[A, I](
    eventType: String,
    aggregateType: String,
    aggregateId: A => I
  )(using
    aid: AggregateId[I],
    codec: EventCodec[A]
  ): DomainEventDescriptor[A] =
    new DomainEventDescriptor(eventType, aggregateType, a => aid.toUuid(aggregateId(a)))
}
