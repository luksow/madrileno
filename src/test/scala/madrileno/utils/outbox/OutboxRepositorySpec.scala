package madrileno.utils.outbox

import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import madrileno.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class OutboxRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repository = new OutboxRepository

  "OutboxRepository" should {
    "append an event and read it back by aggregate, round-tripping the jsonb payload" in withRollback {
      val aggregateId = TestData.randomUuid()
      val event       = DomainEvent(
        id = DomainEventId(TestData.randomUuid()),
        eventType = "sample-event.v1",
        aggregateType = "sample",
        aggregateId = aggregateId,
        payload = Json.obj("name" -> Json.fromString("alice"), "count" -> Json.fromInt(3)),
        occurredAt = Instant.parse("2026-06-28T10:00:00Z")
      )

      for {
        _     <- repository.append(event)
        found <- repository.findByAggregate("sample", aggregateId)
      } yield {
        found should have size 1
        found.head shouldBe event
        found.head.payload shouldBe Json.obj("name" -> Json.fromString("alice"), "count" -> Json.fromInt(3))
      }
    }
  }
}
