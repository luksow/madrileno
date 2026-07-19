package madrileno.utils.events.outbox

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.DBInTransaction
import madrileno.utils.task.SchedulerClient

import java.time.Instant

class Outbox(
  outboxRepository: OutboxRepository,
  deliveryRepository: OutboxDeliveryRepository,
  schedulerClient: SchedulerClient,
  dispatcher: OutboxDispatcher
)(using
  UUIDGen[IO],
  Clock[IO]) {

  def publishTransactionally[A](payload: A)(using d: DomainEventDescriptor[A]): DBInTransaction[Unit] =
    for {
      id  <- IdGenerator.generateId(DomainEventId)
      now <- Clock[IO].realTimeInstant
      event = DomainEvent(id, d.eventType, d.aggregateType, d.aggregateId(payload), d.codec.encode(payload), now)
      _ <- outboxRepository.append(event)
      _ <- fanOut(event, now)
    } yield ()

  private def fanOut(event: DomainEvent, now: Instant): DBInTransaction[Unit] =
    dispatcher.consumersFor(event.eventType).traverse_ { consumer =>
      deliveryRepository.openDelivery(event.id, consumer, now).flatMap {
        case true  => schedulerClient.scheduleTransactionally(dispatcher.instanceFor(consumer, event.id)).void
        case false => IO.unit
      }
    }
}
