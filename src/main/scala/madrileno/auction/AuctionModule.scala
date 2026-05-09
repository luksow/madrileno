package madrileno.auction

import cats.effect.IO
import com.softwaremill.macwire.*
import madrileno.auction.domain.AuctionEvent
import madrileno.auction.emails.{AuctionClosedEmailTemplate, OutbidEmailTemplate}
import madrileno.auction.gateways.{VivinoGateway, VivinoGatewayLive}
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository, BidRepository}
import madrileno.auction.routers.{AuctionImageRouter, AuctionRouter}
import madrileno.auction.services.{AuctionImageService, AuctionService}
import madrileno.auth.domain.AuthContext
import madrileno.main.AppConfig
import madrileno.user.repositories.UserRepository
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.utils.http.{AuthRouteProvider, RateLimiterRuntime, RouteProvider, WsRouteProvider}
import madrileno.utils.mailer.{MailPreview, MailPreviewProvider, Mailer}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.storage.{ObjectStore, SignedUrlTtl}
import madrileno.utils.task.{OneTimeTask, OneTimeTaskProvider, RecurringTaskProvider, SchedulerClient, Task}
import org.http4s.server.websocket.WebSocketBuilder2
import pl.iterators.stir.server.Route
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

import scala.concurrent.duration.DurationInt

trait AuctionModule
    extends RouteProvider
    with AuthRouteProvider
    with WsRouteProvider
    with RecurringTaskProvider
    with OneTimeTaskProvider
    with MailPreviewProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  val eventBusRuntime: EventBusRuntime
  val rateLimiterRuntime: RateLimiterRuntime
  val objectStore: ObjectStore
  val schedulerClient: SchedulerClient
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  lazy val userRepository: UserRepository
  lazy val mailer: Mailer
  lazy val appConfig: AppConfig

  protected lazy val vivinoGateway: VivinoGateway            = wire[VivinoGatewayLive]
  protected lazy val auctionEventBus: EventBus[AuctionEvent] = eventBusRuntime.topic[AuctionEvent]("auction_events", maxQueued = 64)
  protected lazy val signedUrlTtl: SignedUrlTtl              = SignedUrlTtl(5.minutes)

  private val auctionRepository        = wire[AuctionRepository]
  private val bidRepository            = wire[BidRepository]
  private val auctionImageRepository   = wire[AuctionImageRepository]
  private val auctionService           = wire[AuctionService]
  private lazy val auctionImageService = wire[AuctionImageService]
  private val auctionRouter            = wire[AuctionRouter]
  private lazy val auctionImageRouter  = new AuctionImageRouter(auctionImageService, appConfig.apiVersion)

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ auctionRouter.authedRoutes(auth) ~ auctionImageRouter.authedRoutes(auth)
  }

  override abstract def route: Route = {
    super.route ~ auctionRouter.routes ~ auctionImageRouter.routes
  }

  override abstract def wsRoutes(wsb: WebSocketBuilder2[IO]): Route = {
    super.wsRoutes(wsb) ~ auctionRouter.wsRoutes(wsb)
  }

  override abstract def recurringTasks: List[Task[?]] = {
    super.recurringTasks :+ auctionService.closeExpiredAuctionsTask
  }

  override abstract def oneTimeTasks: List[OneTimeTask[?]] = {
    super.oneTimeTasks :+ auctionImageService.analyzeImageTask :+ auctionImageService.generateVariantTask
  }

  override abstract def mailPreviews: List[MailPreview] = {
    super.mailPreviews :+ OutbidEmailTemplate.preview :+ AuctionClosedEmailTemplate.sellerPreview :+ AuctionClosedEmailTemplate.winnerPreview
  }
}
