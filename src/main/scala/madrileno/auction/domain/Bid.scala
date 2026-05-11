package madrileno.auction.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.{Currency, UUID}

opaque type BidId = UUID
object BidId extends Opaque[BidId, UUID]

opaque type BidderRef = Int
object BidderRef extends Opaque[BidderRef, Int] {
  override def validate(value: Int): Either[String, BidderRef] =
    if (value >= 1) Right(value) else Left("Bidder ref must be at least 1")
}

final case class Bid(
  id: BidId,
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price,
  createdAt: Instant)

final case class BidHistoryEntry(
  amount: Price,
  currency: Currency,
  bidderRef: BidderRef,
  createdAt: Instant)

object BidHistoryEntry {
  def fromBids(bids: List[Bid], currency: Currency): List[BidHistoryEntry] = {
    val byTime      = bids.sortBy(_.createdAt.toEpochMilli)
    val refByBidder = byTime.map(_.bidderId).distinct.zipWithIndex.map { case (bidderId, i) => bidderId -> BidderRef(i + 1) }.toMap
    byTime.reverse.map(bid => BidHistoryEntry(bid.amount, currency, refByBidder(bid.bidderId), bid.createdAt))
  }
}
