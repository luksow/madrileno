package madrileno.utils.outbox

import cats.effect.IO
import io.circe.Json
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant
import java.util.UUID

private[outbox] object DomainEventTable extends Table[DomainEvent]("domain_event") {
  val id: Column[DomainEventId]     = column("id", uuid.as[DomainEventId])
  val eventType: Column[String]     = column("event_type", text)
  val aggregateType: Column[String] = column("aggregate_type", text)
  val aggregateId: Column[UUID]     = column("aggregate_id", uuid)
  val payload: Column[Json]         = column("payload", jsonb)
  val occurredAt: Column[Instant]   = column("occurred_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[DomainEvent]) =
    (id, eventType, aggregateType, aggregateId, payload, occurredAt)
}

class OutboxRepository {
  def append(event: DomainEvent): DBInTransaction[Unit] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"INSERT INTO ${DomainEventTable.n} (${DomainEventTable.*}) VALUES (${DomainEventTable.c})".command)(event)
      .void
  }

  def findByAggregate(aggregateType: String, aggregateId: UUID): DB[List[DomainEvent]] = {
    val session = summon[Session[IO]]
    session.execute(sql"""SELECT ${DomainEventTable.*} FROM ${DomainEventTable.n}
            WHERE ${DomainEventTable.aggregateType.n} = ${DomainEventTable.aggregateType.c}
              AND ${DomainEventTable.aggregateId.n} = ${DomainEventTable.aggregateId.c}
            ORDER BY ${DomainEventTable.id.n}""".query(DomainEventTable.c))((aggregateType, aggregateId))
  }
}
