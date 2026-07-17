package madrileno.utils.events.outbox

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.db.transactor.DBInTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import skunk.Session
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.{Instant, ZoneOffset}

class OutboxDeliveryRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val outbox   = new OutboxRepository
  private lazy val delivery = new OutboxDeliveryRepository

  private def event(eventType: String): DomainEvent =
    DomainEvent(
      id = DomainEventId(TestData.randomUuid()),
      eventType = eventType,
      aggregateType = "sample",
      aggregateId = TestData.randomUuid(),
      payload = Json.obj("k" -> Json.fromString("v")),
      occurredAt = Instant.parse("2026-06-28T10:00:00Z")
    )

  private def insertScheduledTask(
    taskName: String,
    taskInstance: String,
    now: Instant
  ): DBInTransaction[Unit] = {
    val session = summon[Session[IO]]
    session
      .execute(sql"""INSERT INTO scheduled_task (task_name, task_instance, task_data, next_execution, picked, version, priority)
              VALUES ($text, $text, $jsonb, $timestamptz, $bool, $int8, $int2)""".command)(
        (taskName, taskInstance, Json.obj(), now.atOffset(ZoneOffset.UTC), false, 0L, 0.toShort)
      )
      .void
  }

  "OutboxDeliveryRepository" should {
    "openDelivery is idempotent on (event_id, consumer)" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        first  <- delivery.openDelivery(e.id, "billing", now)
        second <- delivery.openDelivery(e.id, "billing", now)
        status <- delivery.lockForDelivery(e.id, "billing")
      } yield {
        first shouldBe true
        second shouldBe false
        status shouldBe Some(DeliveryStatus.Pending)
      }
    }

    "markCompleted and markFailed transition status" in withRollback {
      val e1  = event("sample-event.v1")
      val e2  = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _  <- outbox.append(e1)
        _  <- outbox.append(e2)
        _  <- delivery.openDelivery(e1.id, "billing", now)
        _  <- delivery.openDelivery(e2.id, "billing", now)
        _  <- delivery.markCompleted(e1.id, "billing", now)
        _  <- delivery.markFailed(e2.id, "billing", Some("boom"), now)
        s1 <- delivery.lockForDelivery(e1.id, "billing")
        s2 <- delivery.lockForDelivery(e2.id, "billing")
      } yield {
        s1 shouldBe Some(DeliveryStatus.Completed)
        s2 shouldBe Some(DeliveryStatus.Failed)
      }
    }

    "deliveriesMissingFor returns only events of subscribed types with no ledger row for the consumer" in withRollback {
      val matching  = event("order-created.v1")
      val otherType = event("user-deleted.v1")
      val already   = event("order-created.v1")
      val now       = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _       <- outbox.append(matching)
        _       <- outbox.append(otherType)
        _       <- outbox.append(already)
        _       <- delivery.openDelivery(already.id, "billing", now)
        missing <- delivery.deliveriesMissingFor("billing", List("order-created.v1"), 100)
      } yield {
        missing.map(_.id) should contain(matching.id)
        missing.map(_.id) should not contain otherType.id
        missing.map(_.id) should not contain already.id
      }
    }

    "pendingWithoutTask returns pending deliveries with no matching scheduled task" in withRollback {
      val e1  = event("sample-event.v1")
      val e2  = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _    <- outbox.append(e1)
        _    <- outbox.append(e2)
        _    <- delivery.openDelivery(e1.id, "billing", now)
        _    <- delivery.openDelivery(e2.id, "billing", now)
        both <- delivery.pendingWithoutTask(OutboxDeliveryTasks.TaskNamePrefix, 100)
        _    <- insertScheduledTask(OutboxDeliveryTasks.taskName("billing"), OutboxDeliveryTasks.taskInstance(e1.id), now)
        one  <- delivery.pendingWithoutTask(OutboxDeliveryTasks.TaskNamePrefix, 100)
        _    <- delivery.markCompleted(e2.id, "billing", now)
        none <- delivery.pendingWithoutTask(OutboxDeliveryTasks.TaskNamePrefix, 100)
      } yield {
        both should contain theSameElementsAs List((e1.id, "billing"), (e2.id, "billing"))
        one shouldBe List((e2.id, "billing"))
        none shouldBe empty
      }
    }

    "recordError sets last_error without touching pending status, and markFailed(None) preserves it" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        _      <- delivery.openDelivery(e.id, "billing", now)
        _      <- delivery.recordError(e.id, "billing", "attempt 1 boom", now)
        status <- delivery.lockForDelivery(e.id, "billing")
        _      <- delivery.markFailed(e.id, "billing", None, now)
        error  <- lastError(e.id, "billing")
      } yield {
        status shouldBe Some(DeliveryStatus.Pending)
        error shouldBe Some("attempt 1 boom")
      }
    }

    "recordError does not touch terminal rows and loadEvent round-trips" in withRollback {
      val e   = event("sample-event.v1")
      val now = Instant.parse("2026-06-28T10:00:01Z")
      for {
        _      <- outbox.append(e)
        _      <- delivery.openDelivery(e.id, "billing", now)
        _      <- delivery.markCompleted(e.id, "billing", now)
        _      <- delivery.recordError(e.id, "billing", "late error", now)
        error  <- lastError(e.id, "billing")
        loaded <- outbox.loadEvent(e.id)
        absent <- outbox.loadEvent(DomainEventId(TestData.randomUuid()))
      } yield {
        error shouldBe None
        loaded shouldBe Some(e)
        absent shouldBe None
      }
    }
  }

  private def lastError(eventId: DomainEventId, consumer: String): DBInTransaction[Option[String]] = {
    val session = summon[Session[IO]]
    session.unique(sql"""SELECT last_error FROM outbox_delivery
                         WHERE event_id = $uuid AND consumer = $text""".query(text.opt))((eventId.unwrap, consumer))
  }
}
