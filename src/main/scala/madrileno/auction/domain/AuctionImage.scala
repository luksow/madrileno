package madrileno.auction.domain

import madrileno.utils.storage.StorageKey
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type AuctionImageId = UUID
object AuctionImageId extends Opaque[AuctionImageId, UUID]

opaque type ContentType = String
object ContentType extends Opaque[ContentType, String] {
  override def validate(value: String): Either[String, ContentType] =
    if (value.trim.nonEmpty) Right(value.trim) else Left("Content type must not be empty")
}

opaque type SizeBytes = Long
object SizeBytes extends Opaque[SizeBytes, Long] {
  override def validate(value: Long): Either[String, SizeBytes] =
    if (value >= 0) Right(value) else Left("Size must be non-negative")
}

opaque type ImagePosition = Int
object ImagePosition extends Opaque[ImagePosition, Int] {
  override def validate(value: Int): Either[String, ImagePosition] =
    if (value >= 0) Right(value) else Left("Position must be non-negative")
}

final case class AuctionImage(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  contentType: ContentType,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant])
