package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

case class AuctionClosedDto(
  auctionId: AuctionId,
  winningBid: Option[BidDto],
  at: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionClosedDto {
  def apply(event: AuctionEvent.AuctionClosed): AuctionClosedDto =
    AuctionClosedDto(event.auctionId, event.winningBid.map(BidDto(_)), event.at)
}
