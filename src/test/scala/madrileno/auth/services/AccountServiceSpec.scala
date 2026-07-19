package madrileno.auth.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import madrileno.auth.domain.*
import madrileno.auth.repositories.*
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.events.outbox.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.time.Instant

class AccountServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val fixedInstant  = Instant.parse("2026-06-28T10:00:00Z")
  private given Clock[IO]   = TestGivens.fixedClock(fixedInstant)
  private given UUIDGen[IO] = TestGivens.deterministicUUIDs()

  given TelemetryContext = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], io.opentelemetry.api.OpenTelemetry.noop())

  private val now = fixedInstant

  private lazy val userRepo           = new UserRepository
  private lazy val userAuthRepo       = new UserAuthRepository
  private lazy val refreshTokenRepo   = new RefreshTokenRepository
  private lazy val outboxRepository   = new OutboxRepository
  private lazy val deliveryRepository = new OutboxDeliveryRepository
  private lazy val schedulerClient    = Scheduler(transactor, SchedulerConfig()).client

  private lazy val dispatcher = new OutboxDispatcher(Nil, outboxRepository, deliveryRepository, transactor, OutboxConfig())
  private lazy val outbox     = new Outbox(outboxRepository, deliveryRepository, schedulerClient, dispatcher)

  private lazy val accountService = new AccountService(userRepo, userAuthRepo, refreshTokenRepo, outbox, transactor)

  private def seed(user: User): IO[Unit] =
    transactor.inTransaction {
      userRepo.create(user, now) *>
        userAuthRepo.save(UserAuth(TestData.randomUserAuthId(), user.id, TestData.verifiedExternalToken()), now) *>
        refreshTokenRepo.save(TestData.refreshToken(userId = user.id)).as(())
    }

  "AccountService.deleteAccount" should {
    "anonymizes, revokes auth, and publishes exactly one event — idempotently" in {
      val user = TestData.user()
      for {
        _      <- seed(user)
        first  <- accountService.deleteAccount(user.id)
        second <- accountService.deleteAccount(user.id)
        events <- transactor.inSession(outboxRepository.findByAggregate("user", user.id.unwrap))
        found  <- transactor.inSession(userRepo.find(user.id))
        tokens <- transactor.inSession(refreshTokenRepo.listActive(user.id, now))
      } yield {
        first shouldBe DeleteAccountResult.Deleted
        second shouldBe DeleteAccountResult.AlreadyDeleted
        events should have size 1
        events.head.eventType shouldBe "user-account-deleted.v1"
        found shouldBe None
        tokens shouldBe empty
      }
    }
  }
}
