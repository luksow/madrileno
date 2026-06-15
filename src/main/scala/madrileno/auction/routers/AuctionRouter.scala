package madrileno.auction.routers

import cats.effect.IO
import fs2.Stream
import madrileno.auction.domain.*
import madrileno.auction.routers.dto.*
import madrileno.auction.services.*
import madrileno.auth.domain.AuthContext
import madrileno.user.domain.UserId
import madrileno.utils.events.EventBus
import madrileno.utils.http.{BaseRouter, RateLimitDirectives, RateLimiterRuntime}
import madrileno.utils.observability.TelemetryContext
import org.http4s.Request
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import scala.concurrent.duration.*

class AuctionRouter(
  auctionService: AuctionService,
  eventBus: EventBus[AuctionEvent],
  override protected val rateLimiterRuntime: RateLimiterRuntime
)(using TelemetryContext)
    extends BaseRouter
    with RateLimitDirectives {

  val routes: Route = {
    (get & path("auctions") & pathEndOrSingleSlash & parameters("status".as[AuctionStatus].?, "seller-id".as[UserId].?) & paginated(
      AuctionSortField.CreatedAt
    )) {
      (
        status,
        sellerId,
        page
      ) =>
        rateLimited("auctions.list", to = 60, within = 1.minute) {
          complete {
            val filter = ListAuctionsFilter(status = status, sellerId = sellerId, page = page)
            auctionService.listAuctions(filter).map[ToResponseMarshallable] { result =>
              Ok -> result.map(AuctionDto(_))
            }
          }
        }
    } ~
      (get & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) { auctionId =>
        rateLimited("auctions.get", to = 120, within = 1.minute) {
          complete {
            auctionService.getAuction(auctionId).map[ToResponseMarshallable] {
              case Some(view) => Ok -> AuctionDto(view)
              case None       => error(NotFound, "auction-not-found", "Auction not found")
            }
          }
        }
      } ~
      (get & path("auctions" / JavaUUID.as[AuctionId] / "bids") & pathEndOrSingleSlash) { auctionId =>
        rateLimited("auctions.bids", to = 120, within = 1.minute) {
          complete {
            auctionService.listBids(auctionId).map[ToResponseMarshallable] {
              case Some(entries) => Ok -> entries.map(BidHistoryEntryDto(_))
              case None          => error(NotFound, "auction-not-found", "Auction not found")
            }
          }
        }
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    val byUser: Request[IO] => String = _ => s"user:${authContext.userId}"
    (post & path("auctions") & pathEndOrSingleSlash & entity(as[CreateAuctionRequest])) { request =>
      rateLimited("auctions.create", to = 10, within = 1.minute, by = byUser) {
        complete {
          val command = CreateAuctionCommand(
            sellerId = authContext.userId,
            wineName = request.wineName,
            vintage = request.vintage,
            color = request.color,
            region = request.region,
            appellation = request.appellation,
            producerName = request.producerName,
            bottleSize = request.bottleSize,
            bottleCount = request.bottleCount,
            description = request.description,
            startingPrice = request.startingPrice,
            currency = request.currency,
            startsAt = request.startsAt,
            endsAt = request.endsAt
          )
          auctionService.createAuction(command).map[ToResponseMarshallable] {
            case CreateAuctionResult.Created(view) => Created -> AuctionDto(view)
            case CreateAuctionResult.InvalidWindow =>
              error(BadRequest, "invalid-window", "Auction window is invalid: endsAt must be strictly after startsAt and in the future")
          }
        }
      }
    } ~
      (delete & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) { auctionId =>
        rateLimited("auctions.cancel", to = 30, within = 1.minute, by = byUser) {
          complete {
            val command = CancelAuctionCommand(auctionId, authContext.userId)
            auctionService.cancelAuction(command).map[ToResponseMarshallable] {
              case _: CancelAuctionResult.Cancelled    => NoContent
              case CancelAuctionResult.AuctionNotFound => error(NotFound, "auction-not-found", "Auction not found")
              case CancelAuctionResult.NotOwner        => error(Forbidden, "not-owner", "Only the seller can cancel this auction")
              case CancelAuctionResult.AuctionNotOpen  => error(Conflict, "auction-not-open", "Auction is not open")
              case CancelAuctionResult.AuctionEnded    => error(Conflict, "auction-ended", "Auction has already ended")
            }
          }
        }
      } ~
      (post & path("auctions" / JavaUUID.as[AuctionId] / "bids") & pathEndOrSingleSlash & entity(as[PlaceBidRequest])) { (auctionId, request) =>
        rateLimited("auctions.bid", to = 30, within = 1.minute, by = byUser) {
          complete {
            val command = PlaceBidCommand(auctionId, authContext.userId, request.amount)
            auctionService.placeBid(command).map[ToResponseMarshallable] {
              case PlaceBidResult.BidPlaced(bid, _)     => Created -> BidDto(bid)
              case PlaceBidResult.AuctionNotFound       => error(NotFound, "auction-not-found", "Auction not found")
              case PlaceBidResult.AuctionNotOpen        => error(Conflict, "auction-not-open", "Auction is not open")
              case PlaceBidResult.AuctionNotStarted     => error(Conflict, "auction-not-started", "Auction has not started yet")
              case PlaceBidResult.AuctionEnded          => error(Conflict, "auction-ended", "Auction has already ended")
              case PlaceBidResult.CannotBidOnOwnAuction => error(Forbidden, "cannot-bid-on-own-auction", "Cannot bid on your own auction")
              case PlaceBidResult.AlreadyHighestBidder  => error(Conflict, "already-highest-bidder", "You already have the highest bid")
              case PlaceBidResult.BidTooLow(currentHighest) =>
                error(Conflict, "bid-too-low", "Bid is below the current minimum", extension = Map("minAmount" -> currentHighest))
            }
          }
        }
      }
  }

  def wsRoutes(wsb: WebSocketBuilder2[IO]): Route = {
    def auctionEventStream(keep: AuctionEvent => Boolean): Route = {
      val send = Stream
        .resource(eventBus.subscribeAwait)
        .flatMap(_.filter(keep).droppingBuffer(capacity = 256).map(e => WebSocketFrame.Text(AuctionEventEnvelope(e).noSpaces)))
      handleWebSocketMessages(wsb, send, _.drain)
    }
    (get & path("auctions" / "stream") & pathEndOrSingleSlash) {
      auctionEventStream(_ => true)
    } ~ (get & path("auctions" / JavaUUID.as[AuctionId] / "stream") & pathEndOrSingleSlash) { auctionId =>
      auctionEventStream(_.auctionId == auctionId)
    }
  }
}
