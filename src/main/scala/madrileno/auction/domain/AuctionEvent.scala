package madrileno.auction.domain

import io.scalaland.chimney.dsl.*
import madrileno.utils.events.EventCodec
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

enum AuctionEvent derives EventCodec {
  def auctionId: AuctionId
  def at: Instant

  case AuctionCreated(
    auctionId: AuctionId,
    wineName: WineName,
    startingPrice: Price,
    endsAt: Instant,
    at: Instant)

  case BidPlaced(
    auctionId: AuctionId,
    amount: Price,
    at: Instant)

  case AuctionCancelled(auctionId: AuctionId, at: Instant)

  case AuctionClosed(
    auctionId: AuctionId,
    winningBid: Option[Bid],
    at: Instant)
}

object AuctionEvent {

  object AuctionCreated {
    def from(view: AuctionView): AuctionCreated =
      view
        .into[AuctionCreated]
        .withFieldRenamed(_.id, _.auctionId)
        .withFieldRenamed(_.createdAt, _.at)
        .transform
  }

  object BidPlaced {
    def from(bid: Bid): BidPlaced =
      bid
        .into[BidPlaced]
        .withFieldRenamed(_.createdAt, _.at)
        .transform
  }

  object AuctionCancelled {
    def from(auction: Auction): AuctionCancelled = AuctionCancelled(auction.id, auction.updatedAt)
  }

  object AuctionClosed {
    def from(auction: Auction, winningBid: Option[Bid]): AuctionClosed = AuctionClosed(auction.id, winningBid, auction.updatedAt)
  }
}
