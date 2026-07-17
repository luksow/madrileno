package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Clock, IO}
import io.opentelemetry.api.OpenTelemetry
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.auth.repositories.{RefreshTokenRepository, UserAuthRepository}
import madrileno.auth.services.AccountService
import madrileno.support.{TestData, TestGivens, TestTransactor}
import madrileno.user.domain.{UserAccountDeleted, UserId}
import madrileno.user.repositories.UserRepository
import madrileno.utils.events.outbox.*
import madrileno.utils.mailer.*
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{Scheduler, SchedulerConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

import java.net.URI
import java.time.Instant

class AuctionAccountCleanupSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given TelemetryContext    = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], OpenTelemetry.noop())
  private given Clock[IO]   = TestGivens.fixedClock()
  private given UUIDGen[IO] = TestGivens.deterministicUUIDs()

  private lazy val userRepo     = new UserRepository
  private lazy val auctionRepo  = new AuctionRepository
  private lazy val bidRepo      = new BidRepository
  private lazy val outboxRepo   = new OutboxRepository
  private lazy val deliveryRepo = new OutboxDeliveryRepository

  private lazy val scheduler  = Scheduler(transactor, SchedulerConfig())
  private lazy val smtpSender = SmtpSender(MailerConfig(host = "localhost", port = 25, fromAddress = "test@example.com", tls = false))
  private lazy val mailer     = new Mailer(smtpSender, scheduler.client, MailContext(baseUrl = URI("https://example.com")))

  private lazy val service = new AuctionAccountCleanupService(auctionRepo, bidRepo, userRepo, mailer)

  private val seller          = TestData.user()
  private val bidder          = TestData.user()
  private val outsider        = TestData.user()
  private val openAuction     = TestData.auction(sellerId = seller.id)
  private val closedAuction   = TestData.auction(sellerId = seller.id, status = AuctionStatus.Closed)
  private val outsiderAuction = TestData.auction(sellerId = outsider.id)
  private val seedBid         = TestData.bid(auctionId = openAuction.id, bidderId = bidder.id)

  private def seedAll: IO[Unit] =
    transactor.inTransaction {
      val now = Instant.now()
      userRepo.create(seller, now) *>
        userRepo.create(bidder, now) *>
        userRepo.create(outsider, now) *>
        auctionRepo.save(openAuction) *>
        auctionRepo.save(closedAuction) *>
        auctionRepo.save(outsiderAuction) *>
        bidRepo.save(seedBid).void
    }

  private def cleanup: IO[Unit] =
    for {
      events <- transactor.inSession(outboxRepo.findByAggregate("user", seller.id.unwrap))
      _      <- transactor.inSession {
             val session = summon[Session[IO]]
             events.foldLeft(IO.unit) { (acc, e) =>
               val id    = e.id.unwrap
               val idStr = id.toString
               acc *>
                 session.execute(sql"DELETE FROM scheduled_task WHERE task_instance = $text".command)(idStr).as(()) *>
                 session.execute(sql"DELETE FROM outbox_delivery WHERE event_id = $uuid".command)(id).as(()) *>
                 session.execute(sql"DELETE FROM domain_event WHERE id = $uuid".command)(id).as(())
             } *>
               session.execute(sql"DELETE FROM scheduled_task WHERE task_name = $text".command)("send-mail").as(()) *>
               session.execute(sql"DELETE FROM bid WHERE auction_id = $uuid".command)(openAuction.id.unwrap).as(()) *>
               session.execute(sql"DELETE FROM auction WHERE seller_id = $uuid".command)(seller.id.unwrap).as(()) *>
               session.execute(sql"DELETE FROM auction WHERE seller_id = $uuid".command)(outsider.id.unwrap).as(()) *>
               session.execute(sql"""DELETE FROM "user" WHERE id = $uuid""".command)(seller.id.unwrap).as(()) *>
               session.execute(sql"""DELETE FROM "user" WHERE id = $uuid""".command)(bidder.id.unwrap).as(()) *>
               session.execute(sql"""DELETE FROM "user" WHERE id = $uuid""".command)(outsider.id.unwrap).as(())
           }
    } yield ()

  private def sendMailTaskCount: IO[Long] =
    transactor.inSession {
      val session = summon[Session[IO]]
      session.unique(sql"SELECT count(*) FROM scheduled_task WHERE task_name = $text".query(int8))("send-mail")
    }

  "AuctionAccountCleanupService.onAccountDeleted" should {
    "cancels open auctions, skips closed ones, and enqueues bidder notifications" in {
      (for {
        _        <- seedAll
        reaction <- transactor.inTransaction(service.onAccountDeleted(UserAccountDeleted(seller.id)))
        open     <- transactor.inSession(auctionRepo.find(openAuction.id))
        closed   <- transactor.inSession(auctionRepo.find(closedAuction.id))
        mails    <- sendMailTaskCount
      } yield {
        reaction shouldBe Reaction.Done
        open.map(_.status) shouldBe Some(AuctionStatus.Cancelled)
        closed.map(_.status) shouldBe Some(AuctionStatus.Closed)
        mails shouldBe 1L
      }).guarantee(cleanup)
    }

    "full flow: deleteAccount publishes, delivery cancels the auction and completes the ledger" in {
      val auctionSub = OutboxSubscription[UserAccountDeleted]("auction")(service.onAccountDeleted)
      val dispatcher = new OutboxDispatcher(List(auctionSub), outboxRepo, deliveryRepo, transactor, OutboxConfig())
      val outbox     = new Outbox(outboxRepo, deliveryRepo, scheduler.client, dispatcher)

      val userAuthRepo     = new UserAuthRepository
      val refreshTokenRepo = new RefreshTokenRepository
      val accountService   = new AccountService(userRepo, userAuthRepo, refreshTokenRepo, outbox, transactor)

      def runDelivery(
        d: OutboxDispatcher,
        consumer: String,
        eventId: DomainEventId
      ): IO[Unit] = {
        val task = d.deliveryTasks.find(_.descriptor.taskName == OutboxDeliveryTasks.taskName(consumer)).get
        task.execution(d.instanceFor(consumer, eventId))
      }

      (for {
        _      <- seedAll
        _      <- accountService.deleteAccount(seller.id)
        events <- transactor.inSession(outboxRepo.findByAggregate("user", seller.id.unwrap))
        id = events.head.id
        st0  <- transactor.inTransaction(deliveryRepo.lockForDelivery(id, "auction"))
        _    <- runDelivery(dispatcher, "auction", id)
        st1  <- transactor.inTransaction(deliveryRepo.lockForDelivery(id, "auction"))
        open <- transactor.inSession(auctionRepo.find(openAuction.id))
      } yield {
        st0 shouldBe Some(DeliveryStatus.Pending)
        st1 shouldBe Some(DeliveryStatus.Completed)
        open.map(_.status) shouldBe Some(AuctionStatus.Cancelled)
      }).guarantee(cleanup)
    }
  }
}
