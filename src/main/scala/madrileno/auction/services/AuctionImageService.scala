package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import fs2.Stream
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.user.domain.UserId
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.storage.{ObjectStore, StorageKey}
import org.http4s.Response

import scala.concurrent.duration.FiniteDuration

class AuctionImageService(
  auctionRepository: AuctionRepository,
  auctionImageRepository: AuctionImageRepository,
  objectStore: ObjectStore,
  transactor: Transactor,
  presignTtl: FiniteDuration
)(using
  TelemetryContext,
  UUIDGen[IO],
  Clock[IO])
    extends LoggingSupport {

  def listImages(auctionId: AuctionId): IO[List[AuctionImage]] =
    transactor.inSession(auctionImageRepository.listByAuction(auctionId))

  def attachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    contentType: ContentType,
    sizeBytes: SizeBytes,
    content: Stream[IO, Byte]
  ): IO[AttachImageResult] = {
    transactor
      .inSession(auctionRepository.find(auctionId))
      .flatMap {
        case None                                          => IO.pure(AttachImageResult.AuctionNotFound)
        case Some(auction) if auction.sellerId != sellerId => IO.pure(AttachImageResult.NotOwner)
        case Some(_) =>
          for {
            id  <- IdGenerator.generateId(AuctionImageId)
            now <- Clock[IO].realTimeInstant
            key = StorageKey(s"auctions/${auctionId.unwrap}/images/${id.unwrap}")
            _ <- objectStore.put(key, contentType.unwrap, sizeBytes.unwrap, content)
            persistResult <- transactor.inSession {
                               for {
                                 pos <- auctionImageRepository.nextPosition(auctionId)
                                 image = AuctionImage(
                                           id = id,
                                           auctionId = auctionId,
                                           storageKey = key,
                                           contentType = contentType,
                                           sizeBytes = sizeBytes,
                                           position = ImagePosition(pos),
                                           uploadedAt = now,
                                           deletedAt = None
                                         )
                                 _ <- auctionImageRepository.save(image)
                               } yield image
                             }.attempt
            result <- persistResult match {
                        case Right(image) => IO.pure(AttachImageResult.Attached(image))
                        case Left(t) =>
                          objectStore.delete(key).attempt.flatMap(_ => logger.error(t)(s"Persist failed for $key, storage cleaned up")) *>
                            IO.raiseError(t)
                      }
          } yield result
      }
  }

  def detachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId
  ): IO[DetachImageResult] = {
    transactor
      .inSession {
        for {
          auctionOpt <- auctionRepository.find(auctionId)
          imageOpt   <- auctionImageRepository.find(imageId)
        } yield (auctionOpt, imageOpt)
      }
      .flatMap {
        case (None, _) | (_, None)                                  => IO.pure(DetachImageResult.NotFound)
        case (Some(auction), _) if auction.sellerId != sellerId     => IO.pure(DetachImageResult.NotOwner)
        case (Some(_), Some(image)) if image.auctionId != auctionId => IO.pure(DetachImageResult.NotFound)
        case (Some(_), Some(image)) =>
          for {
            now <- Clock[IO].realTimeInstant
            _   <- transactor.inSession(auctionImageRepository.softDelete(imageId, now))
            _ <- objectStore.delete(image.storageKey).attempt.flatMap {
                   case Left(t)  => logger.warn(t)(s"Storage delete failed for ${image.storageKey.unwrap}; row already soft-deleted")
                   case Right(_) => IO.unit
                 }
          } yield DetachImageResult.Detached
      }
  }

  def reorderImages(
    auctionId: AuctionId,
    sellerId: UserId,
    orderedIds: List[AuctionImageId]
  ): IO[ReorderImagesResult] = {
    transactor.inTransaction {
      for {
        auctionOpt <- auctionRepository.find(auctionId)
        result <- auctionOpt match {
                    case None                                          => IO.pure(ReorderImagesResult.AuctionNotFound)
                    case Some(auction) if auction.sellerId != sellerId => IO.pure(ReorderImagesResult.NotOwner)
                    case Some(_) =>
                      for {
                        current <- auctionImageRepository.listByAuction(auctionId)
                        currentIds   = current.map(_.id).toSet
                        requestedIds = orderedIds.toSet
                        result <-
                          if (requestedIds != currentIds || orderedIds.length != current.length)
                            IO.pure(ReorderImagesResult.MismatchedIds)
                          else
                            orderedIds.zipWithIndex
                              .traverse_ { case (id, idx) =>
                                auctionImageRepository.setPosition(id, ImagePosition(idx))
                              }
                              .as(ReorderImagesResult.Reordered)
                      } yield result
                  }
      } yield result
    }
  }

  def serveImage(imageId: AuctionImageId): IO[Option[Response[IO]]] =
    transactor.inSession(auctionImageRepository.find(imageId)).flatMap {
      case None        => IO.pure(None)
      case Some(image) => objectStore.serve(image.storageKey, presignTtl).map(Some(_))
    }
}

enum AttachImageResult {
  case Attached(image: AuctionImage)
  case AuctionNotFound
  case NotOwner
}

enum DetachImageResult {
  case Detached
  case NotFound
  case NotOwner
}

enum ReorderImagesResult {
  case Reordered
  case AuctionNotFound
  case NotOwner
  case MismatchedIds
}
