package madrileno.utils.events.outbox

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.time.Instant

class OutboxRecoverySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private lazy val outbox          = new OutboxRepository
  private lazy val delivery        = new OutboxDeliveryRepository
  private lazy val schedulerClient = Scheduler(transactor, SchedulerConfig()).client
  private val now                  = Instant.parse("2026-07-11T10:00:00Z")

  private def recovery(d: OutboxDispatcher): OutboxRecovery =
    new OutboxRecovery(d, delivery, schedulerClient, transactor, OutboxConfig())

  private def subscribed: OutboxDispatcher =
    new OutboxDispatcher(
      List(OutboxSubscription[DispatchEvent]("billing")(_ => IO.pure(Reaction.Done))),
      outbox,
      delivery,
      transactor,
      OutboxConfig()
    )

  private def cleanup(eventIds: List[DomainEventId]): IO[Unit] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.execute(sql"DELETE FROM scheduled_task WHERE task_name LIKE ${text}".command)(OutboxDeliveryTasks.TaskNamePrefix + "%") *>
        eventIds
          .map(_.unwrap)
          .traverse_(id =>
            session.execute(sql"DELETE FROM outbox_delivery WHERE event_id = $uuid".command)(id) *>
              session.execute(sql"DELETE FROM domain_event WHERE id = $uuid".command)(id)
          )
    }

  private def scheduledCount: IO[Long] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.unique(sql"SELECT count(*) FROM scheduled_task WHERE task_name LIKE ${text}".query(int8))(OutboxDeliveryTasks.TaskNamePrefix + "%")
    }

  private def pendingStatus(id: DomainEventId): IO[Option[DeliveryStatus]] =
    transactor.inTransaction(delivery.lockForDelivery(id, "billing"))

  "OutboxRecovery.recoverOnce" should {
    "case a: creates ledger rows + tasks for events a consumer never got, only for subscribed types" in {
      val d       = subscribed
      val matched =
        DomainEvent(DomainEventId(TestData.randomUuid()), "dispatch-event.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      val other = DomainEvent(DomainEventId(TestData.randomUuid()), "unrelated.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      (for {
        _  <- transactor.inTransaction(outbox.append(matched) *> outbox.append(other))
        _  <- recovery(d).recoverOnce
        s1 <- pendingStatus(matched.id)
        s2 <- pendingStatus(other.id)
        n  <- scheduledCount
        _  <- recovery(d).recoverOnce
        n2 <- scheduledCount
      } yield {
        s1 shouldBe Some(DeliveryStatus.Pending)
        s2 shouldBe None
        n shouldBe 1L
        n2 shouldBe 1L
      }).guarantee(cleanup(List(matched.id, other.id)))
    }

    "case b: re-enqueues a pending ledger row whose task vanished" in {
      val d = subscribed
      val e = DomainEvent(DomainEventId(TestData.randomUuid()), "dispatch-event.v1", "dispatch", TestData.randomUuid(), io.circe.Json.obj(), now)
      (for {
        _ <- transactor.inTransaction(outbox.append(e) *> delivery.openDelivery(e.id, "billing", now).void)
        _ <- recovery(d).recoverOnce
        n <- scheduledCount
      } yield n shouldBe 1L).guarantee(cleanup(List(e.id)))
    }
  }
}
