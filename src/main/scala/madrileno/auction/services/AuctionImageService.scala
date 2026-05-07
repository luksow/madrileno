package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.user.domain.UserId
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.storage.{ObjectMetadata, ObjectStore, SignedUrlTtl, StorageKey}
import org.http4s.headers.`Content-Type`
import pl.iterators.sealedmonad.syntax.*

class AuctionImageService(
  auctionRepository: AuctionRepository,
  auctionImageRepository: AuctionImageRepository,
  objectStore: ObjectStore,
  transactor: Transactor,
  signedUrlTtl: SignedUrlTtl
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
    fileName: String,
    contentType: `Content-Type`,
    sizeBytesHint: Long,
    content: Stream[IO, Byte]
  ): IO[AttachImageResult] = {
    (for {
      _ <- transactor
             .inSession(auctionRepository.find(auctionId))
             .valueOr[AttachImageResult](AttachImageResult.AuctionNotFound)
             .ensure(_.sellerId == sellerId, AttachImageResult.NotOwner)
      id      <- IdGenerator.generateId(AuctionImageId).seal
      now     <- Clock[IO].realTimeInstant.seal
      counter <- Ref.of[IO, Long](0L).seal
      key         = StorageKey(s"auctions/$auctionId/images/$id")
      countedBody = content.chunks.evalTap(c => counter.update(_ + c.size)).unchunks
      _          <- objectStore.put(key, ObjectMetadata(contentType, sizeBytesHint), countedBody).seal
      actualSize <- counter.get.seal
      image      <- persistAttached(auctionId, id, key, fileName, contentType, actualSize, now).seal
    } yield AttachImageResult.Attached(image)).run
  }

  private def persistAttached(
    auctionId: AuctionId,
    id: AuctionImageId,
    key: StorageKey,
    fileName: String,
    contentType: `Content-Type`,
    actualSize: Long,
    now: java.time.Instant
  ): IO[AuctionImage] = {
    val persist = transactor.inTransaction {
      for {
        pos <- auctionImageRepository.nextPosition(auctionId)
        image = AuctionImage(
                  id = id,
                  auctionId = auctionId,
                  storageKey = key,
                  fileName = fileName,
                  contentType = contentType,
                  sizeBytes = SizeBytes(actualSize),
                  position = ImagePosition(pos),
                  uploadedAt = now,
                  deletedAt = None
                )
        _ <- auctionImageRepository.save(image)
      } yield image
    }
    persist.onError(t => objectStore.delete(key).attempt *> logger.error(t)(s"Persist failed for $key, storage cleaned up"))
  }

  def detachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId
  ): IO[DetachImageResult] = {
    (for {
      _ <- transactor
             .inSession(auctionRepository.find(auctionId))
             .valueOr[DetachImageResult](DetachImageResult.NotFound)
             .ensure(_.sellerId == sellerId, DetachImageResult.NotOwner)
      image <- transactor
                 .inSession(auctionImageRepository.find(imageId))
                 .valueOr[DetachImageResult](DetachImageResult.NotFound)
                 .ensure(_.auctionId == auctionId, DetachImageResult.NotFound)
      now <- Clock[IO].realTimeInstant.seal
      _   <- transactor.inSession(auctionImageRepository.softDelete(imageId, now)).seal
      _ <- objectStore
             .delete(image.storageKey)
             .attempt
             .flatMap {
               case Left(t)  => logger.warn(t)(s"Storage delete failed for ${image.storageKey.render}; row already soft-deleted")
               case Right(_) => IO.unit
             }
             .seal
    } yield DetachImageResult.Detached).run
  }

  def reorderImages(
    auctionId: AuctionId,
    sellerId: UserId,
    orderedIds: List[AuctionImageId]
  ): IO[ReorderImagesResult] = {
    transactor.inTransaction {
      (for {
        _ <- auctionRepository
               .findForUpdate(auctionId)
               .valueOr[ReorderImagesResult](ReorderImagesResult.AuctionNotFound)
               .ensure(_.sellerId == sellerId, ReorderImagesResult.NotOwner)
        current <- auctionImageRepository.listByAuctionForUpdate(auctionId).seal
        _ <- IO
               .pure(orderedIds)
               .seal
               .ensure(ids => ids.toSet == current.map(_.id).toSet && ids.length == current.length, ReorderImagesResult.MismatchedIds)
        _ <- orderedIds.zipWithIndex.traverse_ { case (id, idx) => auctionImageRepository.setPosition(id, ImagePosition(idx)) }.seal
      } yield ReorderImagesResult.Reordered).run
    }
  }

  def serveImage(auctionId: AuctionId, imageId: AuctionImageId): IO[Option[ObjectStore.GetResult]] =
    transactor.inSession(auctionImageRepository.find(imageId)).flatMap {
      case Some(image) if image.auctionId == auctionId =>
        objectStore.get(image.storageKey, signedUrlTtl, Some(image.fileName)).map {
          case ObjectStore.GetResult.NotFound => None
          case other                          => Some(other)
        }
      case _ => IO.pure(None)
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
