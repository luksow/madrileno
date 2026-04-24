package madrileno.auction.domain

import java.time.Instant

sealed trait AuctionEvent {
  def auctionId: AuctionId
  def at: Instant
}

object AuctionEvent {

  case class AuctionCreated(
    auctionId: AuctionId,
    wineName: WineName,
    startingPrice: Price,
    endsAt: Instant,
    at: Instant)
      extends AuctionEvent

  case class BidPlaced(
    auctionId: AuctionId,
    amount: Price,
    at: Instant)
      extends AuctionEvent

  case class AuctionCancelled(auctionId: AuctionId, at: Instant) extends AuctionEvent

  case class AuctionClosed(
    auctionId: AuctionId,
    winningBid: Option[Price],
    at: Instant)
      extends AuctionEvent
}
