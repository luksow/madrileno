package madrileno.auction.services

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import fs2.Stream
import madrileno.auction.domain.*
import madrileno.auction.repositories.{AuctionImageRepository, AuctionRepository}
import madrileno.user.domain.UserId
import madrileno.utils.crypto.IdGenerator
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.storage.{ObjectStat, ObjectStore, PresignedPut, SignedUrlTtl, StorageKey}
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
    content: Stream[IO, Byte]
  ): IO[AttachImageResult] = {
    transactor.inSession(auctionRepository.find(auctionId)).flatMap {
      case None                                          => IO.pure(AttachImageResult.AuctionNotFound)
      case Some(auction) if auction.sellerId != sellerId => IO.pure(AttachImageResult.NotOwner)
      case Some(_) =>
        for {
          id  <- IdGenerator.generateId(AuctionImageId)
          now <- Clock[IO].realTimeInstant
          key = StorageKey(s"auctions/$auctionId/images/$id")
          actualSize <- objectStore.put(key, contentType, content)
          result <- persistAttached(auctionId, sellerId, id, key, fileName, contentType, actualSize, now).attempt.flatMap {
                      case Right(AttachImageResult.Attached(image)) => IO.pure(AttachImageResult.Attached(image))
                      case Right(other)                             => cleanupAfterFailedPersist(key) *> IO.pure(other)
                      case Left(t) => cleanupAfterFailedPersist(key) *> logger.error(t)(s"Persist failed for ${key.render}") *> IO.raiseError(t)
                    }
        } yield result
    }
  }

  private def persistAttached(
    auctionId: AuctionId,
    sellerId: UserId,
    id: AuctionImageId,
    key: StorageKey,
    fileName: String,
    contentType: `Content-Type`,
    actualSize: Long,
    now: java.time.Instant
  ): IO[AttachImageResult] = {
    transactor.inTransaction {
      (for {
        _ <- auctionRepository
               .findForUpdate(auctionId)
               .valueOr[AttachImageResult](AttachImageResult.AuctionNotFound)
               .ensure(_.sellerId == sellerId, AttachImageResult.NotOwner)
        pos <- auctionImageRepository.nextPosition(auctionId).seal
        image = AuctionImage.newlyAttached(
                  id = id,
                  auctionId = auctionId,
                  storageKey = key,
                  fileName = fileName,
                  contentType = contentType,
                  sizeBytes = SizeBytes(actualSize),
                  position = ImagePosition(pos),
                  uploadedAt = now
                )
        _ <- auctionImageRepository.save(image).seal
      } yield AttachImageResult.Attached(image)).run
    }
  }

  private def cleanupAfterFailedPersist(key: StorageKey): IO[Unit] =
    objectStore.delete(key).attempt.flatMap {
      case Right(_) => IO.unit
      case Left(t)  => logger.warn(t)(s"Storage cleanup failed for ${key.render}; possible orphan blob")
    }

  def detachImage(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId
  ): IO[DetachImageResult] = {
    type Outcome = Either[DetachImageResult, AuctionImage]
    transactor
      .inTransaction {
        (for {
          _ <- auctionRepository
                 .findForUpdate(auctionId)
                 .valueOr[Outcome](Left(DetachImageResult.NotFound))
                 .ensure(_.sellerId == sellerId, Left(DetachImageResult.NotOwner))
          image <- auctionImageRepository
                     .find(imageId)
                     .valueOr[Outcome](Left(DetachImageResult.NotFound))
                     .ensure(_.auctionId == auctionId, Left(DetachImageResult.NotFound))
          now <- Clock[IO].realTimeInstant.seal
          _   <- auctionImageRepository.softDelete(imageId, now).seal
        } yield Right(image): Outcome).run
      }
      .flatMap {
        case Right(image) =>
          objectStore
            .delete(image.storageKey)
            .attempt
            .flatMap {
              case Left(t)  => logger.warn(t)(s"Storage delete failed for ${image.storageKey.render}; row already soft-deleted")
              case Right(_) => IO.unit
            }
            .as(DetachImageResult.Detached)
        case Left(outcome) => IO.pure(outcome)
      }
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
        updates = orderedIds.zipWithIndex.map { case (id, idx) => (id, ImagePosition(idx)) }
        _ <- auctionImageRepository.bulkSetPositions(auctionId, updates).seal
      } yield ReorderImagesResult.Reordered).run
    }
  }

  def presignUpload(
    auctionId: AuctionId,
    sellerId: UserId,
    fileName: String,
    contentType: `Content-Type`,
    contentLength: Long
  ): IO[PresignUploadResult] = {
    val _ = fileName
    (for {
      _ <- transactor
             .inSession(auctionRepository.find(auctionId))
             .valueOr[PresignUploadResult](PresignUploadResult.AuctionNotFound)
             .ensure(_.sellerId == sellerId, PresignUploadResult.NotOwner)
      imageId <- IdGenerator.generateId(AuctionImageId).seal
      key = StorageKey(s"auctions/$auctionId/images/$imageId")
      presigned <- objectStore.presignPut(key, signedUrlTtl, contentType, contentLength).seal
    } yield PresignUploadResult.Presigned(imageId, presigned)).run
  }

  def commitUpload(
    auctionId: AuctionId,
    sellerId: UserId,
    imageId: AuctionImageId,
    fileName: String
  ): IO[CommitUploadResult] = {
    val key = StorageKey(s"auctions/$auctionId/images/$imageId")
    (for {
      _ <- transactor
             .inSession(auctionRepository.find(auctionId))
             .valueOr[CommitUploadResult](CommitUploadResult.AuctionNotFound)
             .ensure(_.sellerId == sellerId, CommitUploadResult.NotOwner)
      stat  <- objectStore.head(key).valueOr[CommitUploadResult](CommitUploadResult.ObjectNotFound)
      now   <- Clock[IO].realTimeInstant.seal
      image <- persistCommittedRow(auctionId, imageId, key, fileName, stat, now).seal
    } yield CommitUploadResult.Committed(image)).run
  }

  private def persistCommittedRow(
    auctionId: AuctionId,
    imageId: AuctionImageId,
    key: StorageKey,
    fileName: String,
    stat: ObjectStat,
    now: java.time.Instant
  ): IO[AuctionImage] =
    transactor.inTransaction {
      for {
        _   <- auctionRepository.findForUpdate(auctionId).void
        pos <- auctionImageRepository.nextPosition(auctionId)
        image = AuctionImage.newlyAttached(
                  id = imageId,
                  auctionId = auctionId,
                  storageKey = key,
                  fileName = fileName,
                  contentType = stat.contentType,
                  sizeBytes = SizeBytes(stat.sizeBytes),
                  position = ImagePosition(pos),
                  uploadedAt = now
                )
        _ <- auctionImageRepository.save(image)
      } yield image
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

enum PresignUploadResult {
  case Presigned(imageId: AuctionImageId, presigned: PresignedPut)
  case AuctionNotFound
  case NotOwner
}

enum CommitUploadResult {
  case Committed(image: AuctionImage)
  case AuctionNotFound
  case NotOwner
  case ObjectNotFound
}
