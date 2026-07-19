package madrileno.utils.events.outbox

import cats.effect.IO
import madrileno.utils.db.transactor.DBInTransaction

final case class OutboxSubscription(
  consumer: String,
  eventType: String,
  run: (DomainEvent, Delivery) => DBInTransaction[Reaction])

object OutboxSubscription {
  def apply[A](consumer: String)(handler: A => DBInTransaction[Reaction])(using DomainEventDescriptor[A]): OutboxSubscription =
    withDelivery[A](consumer)((a, _) => handler(a))

  def withDelivery[A](consumer: String)(handler: (A, Delivery) => DBInTransaction[Reaction])(using d: DomainEventDescriptor[A]): OutboxSubscription =
    OutboxSubscription(
      consumer,
      d.eventType,
      (event, delivery) =>
        d.codec.decode(event.payload) match {
          case Right(a)    => handler(a, delivery)
          case Left(error) => IO.pure(Reaction.Drop(s"payload decode failed: ${error.getMessage}"))
        }
    )
}
