package madrileno.auction.domain

import madrileno.utils.storage.StorageKey
import org.http4s.headers.`Content-Type`
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type AuctionImageId = UUID
object AuctionImageId extends Opaque[AuctionImageId, UUID]

opaque type SizeBytes = Long
object SizeBytes extends Opaque[SizeBytes, Long] {
  override def validate(value: Long): Either[String, SizeBytes] =
    if (value >= 0) Right(value) else Left("Size must be non-negative")
}

opaque type ImagePosition = Int
object ImagePosition extends Opaque[ImagePosition, Int] {
  override def validate(value: Int): Either[String, ImagePosition] =
    if (value >= 0) Right(value) else Left("Position must be non-negative")

  given Ordering[ImagePosition] = Ordering[Int].on(_.unwrap)
}

final case class AuctionImage(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  fileName: String,
  contentType: `Content-Type`,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant])
