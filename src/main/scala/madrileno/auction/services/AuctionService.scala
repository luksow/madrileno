package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.auction.domain.*
import madrileno.auction.domain.AuctionEvent.*
import madrileno.auction.emails.{AuctionClosedEmailTemplate, OutbidEmailTemplate}
import madrileno.auction.gateways.VivinoGateway
import madrileno.auction.repositories.*
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.dsl.p
import madrileno.utils.db.transactor.{DB, DBInTransaction, Transactor}
import madrileno.utils.events.EventBus
import madrileno.utils.mailer.{Language, Mailer}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.{CronExpression, Schedule, Task}
import pl.iterators.sealedmonad.syntax.*

import java.time.Instant
import java.util.Currency

class AuctionService(
  auctionRepository: AuctionRepository,
  bidRepository: BidRepository,
  userRepository: UserRepository,
  vivinoGateway: VivinoGateway,
  eventBus: EventBus[AuctionEvent],
  transactor: Transactor,
  mailer: Mailer
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {

  private def publish(event: AuctionEvent): IO[Unit] =
    eventBus.publish(event).handleErrorWith(t => logger.warn(t)(s"Failed to publish $event"))

  def createAuction(command: CreateAuctionCommand): IO[CreateAuctionResult] = {
    transactor
      .inSession {
        for {
          id  <- IdGenerator.generateId(AuctionId)
          now <- Clock[IO].realTimeInstant
          result <- Auction.open(
                      id = id,
                      sellerId = command.sellerId,
                      wineName = command.wineName,
                      vintage = command.vintage,
                      color = command.color,
                      region = command.region,
                      appellation = command.appellation,
                      producerName = command.producerName,
                      bottleSize = command.bottleSize,
                      bottleCount = command.bottleCount,
                      description = command.description,
                      startingPrice = command.startingPrice,
                      currency = command.currency,
                      startsAt = command.startsAt,
                      endsAt = command.endsAt,
                      now = now
                    ) match {
                      case Left(AuctionCreationError.InvalidWindow) =>
                        IO.pure(CreateAuctionResult.InvalidWindow)
                      case Right(auction) =>
                        auctionRepository
                          .save(auction)
                          .as(CreateAuctionResult.Created(AuctionView(auction, currentPrice = auction.startingPrice)))
                    }
        } yield result
      }
      .flatTap {
        case CreateAuctionResult.Created(view) =>
          publish(AuctionCreated(view.id, view.wineName, view.startingPrice, view.endsAt, view.createdAt))
        case _ => IO.unit
      }
  }

  def getAuction(auctionId: AuctionId): IO[Option[AuctionView]] = {
    transactor
      .inSession {
        auctionRepository.find(auctionId).flatMap {
          case Some(row) => resolveCurrentPrice(row).map(price => Some(AuctionView(row.toAuction, price)))
          case None      => IO.pure(None)
        }
      }
      .flatMap {
        case Some(view) => vivinoGateway.findRating(view.wineName, view.vintage).map(r => Some(view.copy(rating = r)))
        case None       => IO.pure(None)
      }
  }

  def listAuctions(filter: ListAuctionsFilter): IO[List[AuctionView]] = {
    transactor.inSession {
      auctionRepository.listWithCurrentPrice(filter.toRowFilter).map { rows =>
        rows.map { case (row, price) => AuctionView(row.toAuction, price) }
      }
    }
  }

  def placeBid(command: PlaceBidCommand): IO[PlaceBidResult] = {
    transactor
      .inTransaction {
        (for {
          row <- auctionRepository
                   .findForUpdate(command.auctionId)
                   .valueOr[PlaceBidResult](PlaceBidResult.AuctionNotFound)
          previousHighest <- bidRepository.highestBid(command.auctionId).seal
          auction = row.toAuction
          now   <- Clock[IO].realTimeInstant.seal
          bidId <- IdGenerator.generateId(BidId).seal
          bid <- auction
                   .placeBid(command.bidderId, command.amount, bidId, now, previousHighest)
                   .left
                   .map {
                     case BidRejection.AuctionNotOpen        => PlaceBidResult.AuctionNotOpen
                     case BidRejection.AuctionNotStarted     => PlaceBidResult.AuctionNotStarted
                     case BidRejection.AuctionEnded          => PlaceBidResult.AuctionEnded
                     case BidRejection.CannotBidOnOwnAuction => PlaceBidResult.CannotBidOnOwnAuction
                     case BidRejection.AlreadyHighestBidder  => PlaceBidResult.AlreadyHighestBidder
                     case BidRejection.BidTooLow(highest)    => PlaceBidResult.BidTooLow(highest)
                   }
                   .rethrow[IO]
          saved <- bidRepository.save(bid).seal
          _     <- notifyOutbid(previousHighest, auction, saved.amount).seal
        } yield PlaceBidResult.BidPlaced(saved)).run
      }
      .flatTap {
        case PlaceBidResult.BidPlaced(bid) => publish(BidPlaced(bid.auctionId, bid.amount, bid.createdAt))
        case _                             => IO.unit
      }
  }

  def cancelAuction(command: CancelAuctionCommand): IO[CancelAuctionResult] = {
    transactor
      .inTransaction {
        (for {
          row <- auctionRepository
                   .findForUpdate(command.auctionId)
                   .valueOr[CancelAuctionResult](CancelAuctionResult.AuctionNotFound)
          auction = row.toAuction
          now <- Clock[IO].realTimeInstant.seal
          cancelled <- auction
                         .cancel(command.sellerId, now)
                         .left
                         .map {
                           case CancellationRejection.NotOwner       => CancelAuctionResult.NotOwner
                           case CancellationRejection.AuctionNotOpen => CancelAuctionResult.AuctionNotOpen
                           case CancellationRejection.AuctionEnded   => CancelAuctionResult.AuctionEnded
                         }
                         .rethrow[IO]
          _ <- auctionRepository.update(cancelled).seal
        } yield CancelAuctionResult.Cancelled).run
      }
      .flatTap {
        case CancelAuctionResult.Cancelled =>
          Clock[IO].realTimeInstant.flatMap(now => publish(AuctionCancelled(command.auctionId, now)))
        case _ => IO.unit
      }
  }

  val closeExpiredAuctionsTask: Task[Unit] = {
    def performClose(closed: Auction): DBInTransaction[Option[Price]] = {
      for {
        winner <- bidRepository.highestBid(closed.id)
        _      <- auctionRepository.update(closed)
        seller <- userRepository.find(closed.sellerId)
        _      <- notifyAuctionClosed(closed, seller, winner)
        _      <- logger.info(s"Closed auction ${closed.id}, winner: ${winner.map(_.bidderId)}")
      } yield winner.map(_.amount)
    }

    def closeOne(auctionId: AuctionId, now: Instant): IO[Unit] = {
      transactor
        .inTransaction {
          auctionRepository.findForUpdate(auctionId).flatMap {
            case Some(lockedRow) =>
              lockedRow.toAuction.close(now) match {
                case Right(closed)                       => performClose(closed).map(winning => Some((closed.id, winning, closed.updatedAt)))
                case Left(CloseRejection.AuctionNotOpen) => IO.pure(None)
              }
            case None => IO.pure(None)
          }
        }
        .flatMap {
          case Some((id, winning, at)) => publish(AuctionClosed(id, winning, at))
          case None                    => IO.unit
        }
    }

    def findExpired(now: Instant): IO[List[AuctionId]] = {
      transactor.inSession {
        auctionRepository
          .list(ListAuctionsFilter(status = Some(AuctionStatus.Open), endsBefore = Some(now)).toRowFilter)
          .map(_.map(_.id))
      }
    }

    Task.recurring("close-expired-auctions", Schedule.Cron(CronExpression.unsafeParse("0 * * ? * *"))) { _ =>
      for {
        now     <- Clock[IO].realTimeInstant
        _       <- logger.info(s"Checking for expired auctions at $now")
        expired <- findExpired(now)
        _ <- expired.traverse_ { auctionId =>
               closeOne(auctionId, now).attempt.flatMap {
                 case Left(err) => logger.warn(err)(s"Failed to close auction $auctionId — skipping")
                 case Right(_)  => IO.unit
               }
             }
      } yield ()
    }
  }

  private def notifyOutbid(
    previousHighest: Option[Bid],
    auction: Auction,
    newBidAmount: Price
  ): DBInTransaction[Unit] = {
    (for {
      prev  <- IO.pure(previousHighest).valueOr(())
      user  <- userRepository.find(prev.bidderId).valueOr(())
      email <- IO.pure(user.emailAddress).valueOr(())
      _ <- mailer
             .sendTransactionally(
               to = List(email.toString),
               template = OutbidEmailTemplate(auction.wineName, newBidAmount, auction.currency),
               lang = Language.En
             )
             .void
             .seal
    } yield ()).run
  }

  private def notifyAuctionClosed(
    auction: Auction,
    seller: Option[User],
    winningBid: Option[Bid]
  ): DBInTransaction[Unit] = {
    val sellerNotification: DBInTransaction[Unit] = (for {
      email <- IO.pure(seller.flatMap(_.emailAddress)).valueOr(())
      _ <- mailer
             .sendTransactionally(
               to = List(email.toString),
               template = AuctionClosedEmailTemplate.forSeller(auction.wineName, winningBid.map(_.amount), auction.currency),
               lang = Language.En
             )
             .void
             .seal
    } yield ()).run

    val winnerNotification: DBInTransaction[Unit] = (for {
      bid    <- IO.pure(winningBid).valueOr(())
      winner <- userRepository.find(bid.bidderId).valueOr(())
      email  <- IO.pure(winner.emailAddress).valueOr(())
      _ <- mailer
             .sendTransactionally(
               to = List(email.toString),
               template = AuctionClosedEmailTemplate.forWinner(auction.wineName, bid.amount, auction.currency),
               lang = Language.En
             )
             .void
             .seal
    } yield ()).run

    sellerNotification *> winnerNotification
  }

  private def resolveCurrentPrice(row: AuctionRow): DB[Price] = {
    bidRepository.highestBid(row.id).map {
      case Some(bid) => bid.amount
      case None      => row.startingPrice
    }
  }

}

case class ListAuctionsFilter(
  status: Option[AuctionStatus] = None,
  sellerId: Option[UserId] = None,
  endsBefore: Option[Instant] = None) {
  def toRowFilter: AuctionRowFilter = AuctionRowFilter(
    status = status.fold(p.any[AuctionStatus])(p.equal),
    sellerId = sellerId.fold(p.any[UserId])(p.equal),
    endsAt = endsBefore.fold(p.any[Instant])(p.lessThanOrEqual)
  )
}

case class CreateAuctionCommand(
  sellerId: UserId,
  wineName: WineName,
  vintage: Option[Vintage],
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
  endsAt: Instant)

case class PlaceBidCommand(
  auctionId: AuctionId,
  bidderId: UserId,
  amount: Price)

case class CancelAuctionCommand(auctionId: AuctionId, sellerId: UserId)

enum PlaceBidResult {
  case BidPlaced(bid: Bid)
  case AuctionNotFound
  case AuctionNotOpen
  case AuctionNotStarted
  case AuctionEnded
  case CannotBidOnOwnAuction
  case AlreadyHighestBidder
  case BidTooLow(currentHighest: Price)
}

enum CreateAuctionResult {
  case Created(auction: AuctionView)
  case InvalidWindow
}

enum CancelAuctionResult {
  case Cancelled
  case AuctionNotFound
  case NotOwner
  case AuctionNotOpen
  case AuctionEnded
}
