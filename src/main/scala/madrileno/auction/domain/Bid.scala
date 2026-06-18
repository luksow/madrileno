package madrileno.auction.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.{Currency, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

opaque type BidId = UUID
object BidId extends Opaque[BidId, UUID]

opaque type BidderRef = String
object BidderRef extends Opaque[BidderRef, String] {
  def forBidder(
    secret: String,
    auctionId: AuctionId,
    bidderId: UserId
  ): BidderRef = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    val tag = mac.doFinal(s"${auctionId.unwrap}:${bidderId.unwrap}".getBytes(UTF_8))
    BidderRef(tag.take(8).map(b => f"$b%02x").mkString)
  }
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
