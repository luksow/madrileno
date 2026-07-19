package madrileno.utils.events.outbox

import io.circe.Json
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*

import java.time.Instant
import java.util.UUID

private[outbox] object DomainEventTable extends Table[DomainEvent]("domain_event") with IdTable[DomainEvent, DomainEventId] {
  override val id: Column[DomainEventId] = column("id", uuid.as[DomainEventId])
  val eventType: Column[String]          = column("event_type", text)
  val aggregateType: Column[String]      = column("aggregate_type", text)
  val aggregateId: Column[UUID]          = column("aggregate_id", uuid)
  val payload: Column[Json]              = column("payload", jsonb)
  val occurredAt: Column[Instant]        = column("occurred_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[DomainEvent]) =
    (id, eventType, aggregateType, aggregateId, payload, occurredAt)
}

private[outbox] final case class DomainEventFilter(aggregateType: SqlPredicate[String] = p.any, aggregateId: SqlPredicate[UUID] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (DomainEventTable.aggregateType, DomainEventTable.aggregateId))
}

class OutboxRepository {
  def append(event: DomainEvent): DBInTransaction[Unit] =
    repository.create(event).void

  def loadEvent(eventId: DomainEventId): DB[Option[DomainEvent]] =
    repository.findById(eventId)

  def findByAggregate(aggregateType: String, aggregateId: UUID): DB[List[DomainEvent]] = {
    val filter = DomainEventFilter(aggregateType = p.equal(aggregateType), aggregateId = p.equal(aggregateId))
    repository.findByFilter(filter).map(_.sortBy(_.id.unwrap.toString))
  }

  private val repository: IdRepository[DomainEvent, DomainEventId] & FilteringRepository[DomainEvent, DomainEventFilter] =
    new IdRepository[DomainEvent, DomainEventId](_.id) with FilteringRepository[DomainEvent, DomainEventFilter] {
      override val table: DomainEventTable.type = DomainEventTable
    }
}
