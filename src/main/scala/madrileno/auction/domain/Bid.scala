package madrileno.auction.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.{Currency, UUID}

opaque type BidId = UUID
object BidId extends Opaque[BidId, UUID]

final case class Bid(
  id: BidId,
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price,
  createdAt: Instant)

final case class BidHistoryEntry(
  amount: Price,
  currency: Currency,
  bidderRef: Int,
  createdAt: Instant)
