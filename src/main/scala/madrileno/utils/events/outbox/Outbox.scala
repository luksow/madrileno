package madrileno.utils.events.outbox

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.DBInTransaction

class Outbox(outboxRepository: OutboxRepository)(using UUIDGen[IO], Clock[IO]) {
  def publishTransactionally[A](payload: A)(using d: DomainEventDescriptor[A]): DBInTransaction[Unit] =
    for {
      id  <- IdGenerator.generateId(DomainEventId)
      now <- Clock[IO].realTimeInstant
      _   <- outboxRepository.append(DomainEvent(id, d.eventType, d.aggregateType, d.aggregateId(payload), d.codec.encode(payload), now))
    } yield ()
}
