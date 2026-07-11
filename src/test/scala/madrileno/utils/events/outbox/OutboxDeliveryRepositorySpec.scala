package madrileno.utils.events.outbox

import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import madrileno.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

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
        _  <- delivery.markFailed(e2.id, "billing", "boom", now)
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
  }
}
