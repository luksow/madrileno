package madrileno.auction

import cats.effect.IO
import com.softwaremill.macwire.*
import madrileno.auction.emails.{AuctionClosedEmailTemplate, OutbidEmailTemplate}
import madrileno.auction.gateways.VivinoGateway
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.auction.routers.AuctionRouter
import madrileno.auction.routers.dto.AuctionEventDto
import madrileno.auction.services.AuctionService
import madrileno.auth.domain.AuthContext
import madrileno.user.repositories.UserRepository
import madrileno.utils.cache.CacheRuntime
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.{EventBus, EventBusRuntime}
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.mailer.{MailPreview, MailPreviewProvider, Mailer}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{RecurringTaskProvider, Task}
import org.http4s.server.websocket.WebSocketBuilder2
import pl.iterators.stir.server.Route
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.WebSocketStreamBackend

trait AuctionModule extends RouteProvider with AuthRouteProvider with RecurringTaskProvider with MailPreviewProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val cacheRuntime: CacheRuntime
  val eventBusRuntime: EventBusRuntime
  lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]]
  lazy val userRepository: UserRepository
  lazy val mailer: Mailer
  def webSocketBuilder: WebSocketBuilder2[IO]

  protected lazy val vivinoGateway: VivinoGateway               = VivinoGateway.live(httpClient, cacheRuntime)
  protected lazy val auctionEventBus: EventBus[AuctionEventDto] = eventBusRuntime.topic[AuctionEventDto]("auction_events")

  private val auctionRepository = wire[AuctionRepository]
  private val bidRepository     = wire[BidRepository]
  private val auctionService    = wire[AuctionService]
  private val auctionRouter     = new AuctionRouter(auctionService, auctionEventBus, () => webSocketBuilder)

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ auctionRouter.authedRoutes(auth)
  }

  override abstract def route: Route = {
    super.route ~ auctionRouter.routes
  }

  override abstract def recurringTasks: List[Task[?]] = {
    super.recurringTasks :+ auctionService.closeExpiredAuctionsTask
  }

  override abstract def mailPreviews: List[MailPreview] = {
    super.mailPreviews :+ OutbidEmailTemplate.preview :+ AuctionClosedEmailTemplate.sellerPreview :+ AuctionClosedEmailTemplate.winnerPreview
  }
}
