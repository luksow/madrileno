package madrileno.auction.routers.dto

import io.circe.Codec as CirceCodec
import madrileno.auction.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

sealed trait AuctionEventDto derives Encoder.AsObject, Decoder

object AuctionEventDto {

  case class AuctionCreated(
    auctionId: AuctionId,
    wineName: WineName,
    startingPrice: Price,
    endsAt: Instant,
    at: Instant)
      extends AuctionEventDto

  case class BidPlaced(
    auctionId: AuctionId,
    amount: Price,
    at: Instant)
      extends AuctionEventDto

  case class AuctionCancelled(auctionId: AuctionId, at: Instant) extends AuctionEventDto

  case class AuctionClosed(
    auctionId: AuctionId,
    winningBid: Option[Price],
    at: Instant)
      extends AuctionEventDto

  def fromDomain(event: AuctionEvent): AuctionEventDto = event match {
    case e: AuctionEvent.AuctionCreated   => AuctionCreated(e.auctionId, e.wineName, e.startingPrice, e.endsAt, e.at)
    case e: AuctionEvent.BidPlaced        => BidPlaced(e.auctionId, e.amount, e.at)
    case e: AuctionEvent.AuctionCancelled => AuctionCancelled(e.auctionId, e.at)
    case e: AuctionEvent.AuctionClosed    => AuctionClosed(e.auctionId, e.winningBid, e.at)
  }

  def toDomain(dto: AuctionEventDto): AuctionEvent = dto match {
    case e: AuctionCreated   => AuctionEvent.AuctionCreated(e.auctionId, e.wineName, e.startingPrice, e.endsAt, e.at)
    case e: BidPlaced        => AuctionEvent.BidPlaced(e.auctionId, e.amount, e.at)
    case e: AuctionCancelled => AuctionEvent.AuctionCancelled(e.auctionId, e.at)
    case e: AuctionClosed    => AuctionEvent.AuctionClosed(e.auctionId, e.winningBid, e.at)
  }

  // Wire codec for domain events routes through the DTO — domain stays free of circe,
  // the bus ships DTO-shaped JSON, and callers (service, bus) only see the domain type.
  given CirceCodec[AuctionEvent] =
    CirceCodec.from(summon[Decoder[AuctionEventDto]].map(toDomain), Encoder.AsObject[AuctionEventDto].contramapObject(fromDomain))
}
