package madrileno.auction.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.{Currency, UUID}
import scala.util.hashing.MurmurHash3

opaque type BidId = UUID
object BidId extends Opaque[BidId, UUID]

opaque type BidderRef = String
object BidderRef extends Opaque[BidderRef, String] {
  def forBidder(auctionId: AuctionId, bidderId: UserId): BidderRef =
    BidderRef(f"${MurmurHash3.stringHash(s"${auctionId.unwrap}:${bidderId.unwrap}")}%08x")
}

final case class Bid(
  id: BidId,
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price,
  createdAt: Instant)

final case class BidHistoryEntry(
  id: BidId,
  amount: Price,
  currency: Currency,
  bidderRef: BidderRef,
  createdAt: Instant)
