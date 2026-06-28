package madrileno.utils.outbox

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.circe.Json
import io.circe.syntax.*
import madrileno.support.{TestData, TestGivens, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.UUID

final case class SampleEvent(userId: UUID, name: String)

object SampleEvent {
  given DomainEventDescriptor[SampleEvent] = new DomainEventDescriptor[SampleEvent] {
    val eventType: String                 = "sample-event.v1"
    val aggregateType: String             = "sample"
    def aggregateId(a: SampleEvent): UUID = a.userId
    def encode(a: SampleEvent): Json      = Json.obj("userId" -> a.userId.toString.asJson, "name" -> a.name.asJson)
  }
}

class DomainEventsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val fixedInstant      = Instant.parse("2026-06-28T10:00:00Z")
  private given Clock[IO]       = TestGivens.fixedClock(fixedInstant)
  private given UUIDGen[IO]     = TestGivens.deterministicUUIDs()
  private lazy val repository   = new OutboxRepository
  private lazy val domainEvents = new DomainEvents(repository)

  "DomainEvents.publishTransactionally" should {
    "stamp a UUIDv7 id + occurred_at and append the event with the versioned type" in withRollback {
      val event = SampleEvent(TestData.randomUuid(), "alice")
      for {
        _     <- domainEvents.publishTransactionally(event)
        found <- repository.findByAggregate("sample", event.userId)
      } yield {
        found should have size 1
        val stored = found.head
        stored.eventType shouldBe "sample-event.v1"
        stored.aggregateType shouldBe "sample"
        stored.aggregateId shouldBe event.userId
        stored.payload shouldBe Json.obj("userId" -> event.userId.toString.asJson, "name" -> "alice".asJson)
        stored.occurredAt shouldBe fixedInstant
        stored.id.unwrap.version() shouldBe 7
      }
    }

    "not persist the event when the surrounding transaction fails" in {
      val event = SampleEvent(TestData.randomUuid(), "bob")
      for {
        result <- transactor.inTransaction {
                    domainEvents.publishTransactionally(event) *> IO.raiseError[Unit](new RuntimeException("boom"))
                  }.attempt
        found <- transactor.inSession(repository.findByAggregate("sample", event.userId))
      } yield {
        result.isLeft shouldBe true
        found shouldBe empty
      }
    }
  }
}
