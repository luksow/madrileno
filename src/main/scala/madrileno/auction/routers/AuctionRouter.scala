package madrileno.auction.routers

import cats.effect.IO
import io.circe.syntax.*
import madrileno.auction.domain.*
import madrileno.auction.routers.dto.*
import madrileno.auction.services.*
import madrileno.auth.domain.AuthContext
import madrileno.user.domain.UserId
import madrileno.utils.events.EventBus
import madrileno.utils.http.BaseRouter
import madrileno.utils.observability.TelemetryContext
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class AuctionRouter(
  auctionService: AuctionService,
  eventBus: EventBus[AuctionEvent],
  wsBuilder: () => WebSocketBuilder2[IO]
)(using TelemetryContext)
    extends BaseRouter {

  val routes: Route = {
    (get & path("auctions") & parameters("status".as[AuctionStatus].?, "seller-id".as[UserId].?) & pathEndOrSingleSlash) { (status, sellerId) =>
      complete {
        val filter = ListAuctionsFilter(status = status, sellerId = sellerId)
        auctionService.listAuctions(filter).map[ToResponseMarshallable] { views =>
          Ok -> views.map(AuctionDto(_))
        }
      }
    } ~
      (get & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) { auctionId =>
        complete {
          auctionService.getAuction(auctionId).map[ToResponseMarshallable] {
            case Some(view) => Ok -> AuctionDto(view)
            case None       => error(NotFound, "auction-not-found", "Auction not found")
          }
        }
      } ~
      (get & path("auctions" / "stream") & pathEndOrSingleSlash) {
        val send = eventBus.subscribe(maxQueued = 64).map(e => WebSocketFrame.Text(AuctionEventDto.fromDomain(e).asJson.noSpaces))
        handleWebSocketMessages(wsBuilder(), send, _.drain)
      }
  }

  def authedRoutes(authContext: AuthContext): Route = {
    (post & path("auctions") & entity(as[CreateAuctionRequest]) & pathEndOrSingleSlash) { request =>
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
    } ~
      (delete & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) { auctionId =>
        complete {
          val command = CancelAuctionCommand(auctionId, authContext.userId)
          auctionService.cancelAuction(command).map[ToResponseMarshallable] {
            case CancelAuctionResult.Cancelled       => NoContent
            case CancelAuctionResult.AuctionNotFound => error(NotFound, "auction-not-found", "Auction not found")
            case CancelAuctionResult.NotOwner        => error(Forbidden, "not-owner", "Only the seller can cancel this auction")
            case CancelAuctionResult.AuctionNotOpen  => error(Conflict, "auction-not-open", "Auction is not open")
            case CancelAuctionResult.AuctionEnded    => error(Conflict, "auction-ended", "Auction has already ended")
          }
        }
      } ~
      (post & path("auctions" / JavaUUID.as[AuctionId] / "bids") & entity(as[PlaceBidRequest]) & pathEndOrSingleSlash) { (auctionId, request) =>
        complete {
          val command = PlaceBidCommand(auctionId, authContext.userId, request.amount)
          auctionService.placeBid(command).map[ToResponseMarshallable] {
            case PlaceBidResult.BidPlaced(bid)        => Created -> BidDto(bid)
            case PlaceBidResult.AuctionNotFound       => error(NotFound, "auction-not-found", "Auction not found")
            case PlaceBidResult.AuctionNotOpen        => error(Conflict, "auction-not-open", "Auction is not open")
            case PlaceBidResult.AuctionNotStarted     => error(Conflict, "auction-not-started", "Auction has not started yet")
            case PlaceBidResult.AuctionEnded          => error(Conflict, "auction-ended", "Auction has already ended")
            case PlaceBidResult.CannotBidOnOwnAuction => error(Forbidden, "cannot-bid-on-own-auction", "Cannot bid on your own auction")
            case PlaceBidResult.AlreadyHighestBidder  => error(Conflict, "already-highest-bidder", "You already have the highest bid")
            case PlaceBidResult.BidTooLow(currentHighest) =>
              error(Conflict, "bid-too-low", s"Bid must be strictly greater than $currentHighest")
          }
        }
      }
  }
}
