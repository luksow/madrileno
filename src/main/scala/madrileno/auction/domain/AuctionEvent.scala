package madrileno.auction.domain

import io.scalaland.chimney.dsl.*
import madrileno.utils.events.EventCodec
import madrileno.utils.events.EventCodec.given

import java.time.Instant
import java.util.Currency

enum AuctionEvent derives EventCodec {
  def auctionId: AuctionId
  def createdAt: Instant

  case AuctionCreated(
    auctionId: AuctionId,
    wineName: WineName,
    startingPrice: Price,
    currency: Currency,
    endsAt: Instant,
    createdAt: Instant)

  case BidPlaced(
    auctionId: AuctionId,
    amount: Price,
    currency: Currency,
    createdAt: Instant)

  case AuctionCancelled(auctionId: AuctionId, createdAt: Instant)

  case AuctionClosed(
    auctionId: AuctionId,
    winningBid: Option[Price],
    currency: Currency,
    createdAt: Instant)
}

object AuctionEvent {

  def auctionCreated(view: AuctionView): AuctionCreated =
    view
      .into[AuctionCreated]
      .enableMethodAccessors
      .withFieldRenamed(_.id, _.auctionId)
      .transform

  def bidPlaced(bid: Bid, auction: Auction): BidPlaced =
    bid
      .into[BidPlaced]
      .withFieldConst(_.currency, auction.currency)
      .transform

  def auctionCancelled(auction: Auction): AuctionCancelled =
    AuctionCancelled(auction.id, auction.updatedAt)

  def auctionClosed(auction: Auction, winningBid: Option[Bid]): AuctionClosed =
    AuctionClosed(auction.id, winningBid.map(_.amount), auction.currency, auction.updatedAt)
}
