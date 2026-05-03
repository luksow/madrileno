package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

case class WinningBidDto(amount: Price) derives Encoder.AsObject, Decoder

case class AuctionClosedDto(
  auctionId: AuctionId,
  winningBid: Option[WinningBidDto],
  at: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionClosedDto {
  def apply(event: AuctionEvent.AuctionClosed): AuctionClosedDto =
    AuctionClosedDto(event.auctionId, event.winningBid.map(b => WinningBidDto(b.amount)), event.at)
}
