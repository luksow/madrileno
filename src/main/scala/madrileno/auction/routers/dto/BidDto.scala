package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

case class BidDto(
  id: BidId,
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price,
  createdAt: Instant)
    derives Encoder.AsObject,
      Decoder

object BidDto {
  def apply(bid: Bid): BidDto = {
    import io.scalaland.chimney.dsl.*
    bid.into[BidDto].transform
  }
}
