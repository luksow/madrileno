package madrileno.auction.domain

import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.{Currency, UUID}
import scala.math.Ordered.orderingToOrdered

opaque type AuctionId = UUID
object AuctionId extends Opaque[AuctionId, UUID]

opaque type WineName = String
object WineName extends Opaque[WineName, String] {
  override def validate(value: String): Either[String, WineName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Wine name must not be empty")
  }
}

opaque type Vintage = Int
object Vintage extends Opaque[Vintage, Int] {
  override def validate(value: Int): Either[String, Vintage] = {
    if (value >= 1800 && value <= 2100) Right(value)
    else Left("Vintage must be between 1800 and 2100")
  }
}

opaque type Region = String
object Region extends Opaque[Region, String] {
  override def validate(value: String): Either[String, Region] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Region must not be empty")
  }
}

opaque type Appellation = String
object Appellation extends Opaque[Appellation, String] {
  override def validate(value: String): Either[String, Appellation] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Appellation must not be empty")
  }
}

opaque type ProducerName = String
object ProducerName extends Opaque[ProducerName, String] {
  override def validate(value: String): Either[String, ProducerName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Producer name must not be empty")
  }
}

opaque type Description = String
object Description extends Opaque[Description, String] {
  override def validate(value: String): Either[String, Description] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Description must not be empty")
  }
}

opaque type Price = BigDecimal
object Price extends Opaque[Price, BigDecimal] {
  override def validate(value: BigDecimal): Either[String, Price] = {
    if (value > 0) Right(value)
    else Left("Price must be positive")
  }

  given Ordering[Price] = Ordering[BigDecimal]
}

opaque type BottleCount = Int
object BottleCount extends Opaque[BottleCount, Int] {
  override def validate(value: Int): Either[String, BottleCount] = {
    if (value >= 1) Right(value)
    else Left("Bottle count must be at least 1")
  }
}

enum WineColor {
  case Red, White, Rose, Orange, Sparkling, Dessert, Fortified
}

enum BottleSize {
  case Half, Standard, Magnum, DoubleMagnum, Jeroboam, Other
}

enum AuctionStatus {
  case Open, Closed, Cancelled
}

enum BidRejection {
  case AuctionNotOpen
  case AuctionNotStarted
  case AuctionEnded
  case CannotBidOnOwnAuction
  case AlreadyHighestBidder
  case BidTooLow(currentHighest: Price)
}

enum CancellationRejection {
  case NotOwner
  case AuctionNotOpen
  case AuctionEnded
}

enum CloseRejection {
  case AuctionNotOpen
}

enum AuctionCreationError {
  case InvalidWindow
}

final case class Auction(
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
  currency: Currency,
  status: AuctionStatus,
  startsAt: Instant,
  endsAt: Instant,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {

  def placeBid(
    bidderId: UserId,
    amount: Price,
    bidId: BidId,
    now: Instant,
    currentHighest: Option[Bid]
  ): Either[BidRejection, Bid] = {
    val floor = currentHighest.map(_.amount).getOrElse(startingPrice)
    if (status != AuctionStatus.Open) Left(BidRejection.AuctionNotOpen)
    else if (now.isBefore(startsAt)) Left(BidRejection.AuctionNotStarted)
    else if (!now.isBefore(endsAt)) Left(BidRejection.AuctionEnded)
    else if (bidderId == sellerId) Left(BidRejection.CannotBidOnOwnAuction)
    else if (currentHighest.exists(_.bidderId == bidderId)) Left(BidRejection.AlreadyHighestBidder)
    else if (amount <= floor) Left(BidRejection.BidTooLow(floor))
    else Right(Bid(bidId, id, bidderId, amount, now))
  }

  def cancel(requesterId: UserId, now: Instant): Either[CancellationRejection, Auction] = {
    if (requesterId != sellerId) Left(CancellationRejection.NotOwner)
    else if (status != AuctionStatus.Open) Left(CancellationRejection.AuctionNotOpen)
    else if (!now.isBefore(endsAt)) Left(CancellationRejection.AuctionEnded)
    else Right(copy(status = AuctionStatus.Cancelled, updatedAt = now))
  }

  def close(now: Instant): Either[CloseRejection, Auction] = {
    if (status != AuctionStatus.Open) Left(CloseRejection.AuctionNotOpen)
    else Right(copy(status = AuctionStatus.Closed, updatedAt = now))
  }
}

object Auction {
  def open(
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
    currency: Currency,
    startsAt: Instant,
    endsAt: Instant,
    now: Instant
  ): Either[AuctionCreationError, Auction] = {
    if (!endsAt.isAfter(startsAt) || !endsAt.isAfter(now)) Left(AuctionCreationError.InvalidWindow)
    else
      Right(
        Auction(
          id = id,
          sellerId = sellerId,
          wineName = wineName,
          vintage = vintage,
          color = color,
          region = region,
          appellation = appellation,
          producerName = producerName,
          bottleSize = bottleSize,
          bottleCount = bottleCount,
          description = description,
          startingPrice = startingPrice,
          currency = currency,
          status = AuctionStatus.Open,
          startsAt = startsAt,
          endsAt = endsAt,
          createdAt = now,
          updatedAt = now,
          deletedAt = None
        )
      )
  }
}

final case class AuctionView(auction: Auction, currentPrice: Price) {
  export auction.*
}
