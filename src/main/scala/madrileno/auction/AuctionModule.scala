package madrileno.auction

import com.softwaremill.macwire.*
import madrileno.auction.emails.{AuctionClosedEmailTemplate, OutbidEmailTemplate}
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.auction.routers.AuctionRouter
import madrileno.auction.services.AuctionService
import madrileno.auth.domain.AuthContext
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.{AuthRouteProvider, RouteProvider}
import madrileno.utils.mailer.{MailPreview, MailPreviewProvider, Mailer}
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{RecurringTaskProvider, Task}
import pl.iterators.stir.server.Route

trait AuctionModule extends RouteProvider with AuthRouteProvider with RecurringTaskProvider with MailPreviewProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  lazy val userRepository: UserRepository
  lazy val mailer: Mailer

  private val auctionRepository = wire[AuctionRepository]
  private val bidRepository     = wire[BidRepository]
  private val auctionService    = wire[AuctionService]
  private val auctionRouter     = wire[AuctionRouter]

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
    super.mailPreviews :+ OutbidEmailTemplate.preview :+ AuctionClosedEmailTemplate.preview
  }
}
