package madrileno.utils.outbox

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.DBInTransaction

class DomainEvents(outboxRepository: OutboxRepository)(using UUIDGen[IO], Clock[IO]) {
  def publishTransactionally[A](payload: A)(using d: DomainEventDescriptor[A]): DBInTransaction[Unit] =
    for {
      id  <- IdGenerator.generateId(DomainEventId)
      now <- Clock[IO].realTimeInstant
      _   <- outboxRepository.append(DomainEvent(id, d.eventType, d.aggregateType, d.aggregateId(payload), d.encode(payload), now))
    } yield ()
}
