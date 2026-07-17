package madrileno.auction.services

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.auction.domain.*
import madrileno.auction.emails.AuctionCancelledEmailTemplate
import madrileno.auction.repositories.{AuctionRepository, BidRepository}
import madrileno.user.domain.UserAccountDeleted
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.DBInTransaction
import madrileno.utils.events.outbox.Reaction
import madrileno.utils.mailer.{Language, Mailer}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import pl.iterators.sealedmonad.syntax.*

class AuctionAccountCleanupService(
  auctionRepository: AuctionRepository,
  bidRepository: BidRepository,
  userRepository: UserRepository,
  mailer: Mailer
)(using
  TelemetryContext,
  Clock[IO])
    extends LoggingSupport {

  def onAccountDeleted(event: UserAccountDeleted): DBInTransaction[Reaction] =
    Clock[IO].realTimeInstant.flatMap { now =>
      auctionRepository
        .listOpenBySeller(event.userId)
        .flatMap {
          _.traverse_ { auction =>
            auctionRepository.update(auction.id, _.cancelBySystem(now)).flatMap {
              case Some(Right(cancelled)) =>
                logger.info(s"cancelled auction ${cancelled.id} after account deletion of ${event.userId}") *>
                  notifyBidders(cancelled)
              case _ => IO.unit
            }
          }
        }
        .as(Reaction.Done)
    }

  private def notifyBidders(auction: Auction): DBInTransaction[Unit] =
    bidRepository.listByAuction(auction.id).flatMap { bids =>
      bids.map(_.bidderId).distinct.filterNot(_ == auction.sellerId).traverse_ { bidderId =>
        (for {
          user  <- userRepository.find(bidderId).valueOr(())
          email <- IO.pure(user.emailAddress).valueOr(())
          _     <- mailer
                 .sendTransactionally(to = List(email.toString), template = AuctionCancelledEmailTemplate(auction.wineName), lang = Language.En)
                 .void
                 .seal
        } yield ()).run
      }
    }
}
