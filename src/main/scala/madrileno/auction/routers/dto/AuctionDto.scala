package madrileno.auction.routers.dto

import madrileno.auction.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant
import java.util.Currency

case class AuctionDto(
  id: AuctionId,
  sellerId: UserId,
  wineName: WineName,
  vintage: Vintage,
  color: WineColor,
  region: Region,
  appellation: Appellation,
  producerName: ProducerName,
  bottleSize: BottleSize,
  bottleCount: BottleCount,
  description: Option[Description],
  startingPrice: Price,
  currentPrice: Price,
  currency: Currency,
  status: AuctionStatus,
  startsAt: Instant,
  endsAt: Instant)
    derives Encoder.AsObject,
      Decoder

object AuctionDto {
  def apply(view: AuctionView): AuctionDto = {
    import io.scalaland.chimney.dsl.*
    view.auction
      .into[AuctionDto]
      .withFieldConst(_.currentPrice, view.currentPrice)
      .transform
  }
}
