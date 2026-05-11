package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

final case class AuctionCancelledDto(auctionId: AuctionId, createdAt: Instant) derives Encoder.AsObject, Decoder

object AuctionCancelledDto {
  def apply(event: AuctionEvent.AuctionCancelled): AuctionCancelledDto = {
    import io.scalaland.chimney.dsl.*
    event.into[AuctionCancelledDto].transform
  }
}
