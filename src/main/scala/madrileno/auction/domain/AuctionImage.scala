package madrileno.auction.domain

import madrileno.utils.imaging.{Height, ImageFormat, Width}
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

enum VariantSpec(val format: ImageFormat) {
  case Thumb  extends VariantSpec(ImageFormat.Jpeg)
  case Medium extends VariantSpec(ImageFormat.Jpeg)
}

object VariantSpec {
  val All: List[VariantSpec]                    = values.toList
  def byName(name: String): Option[VariantSpec] = All.find(_.toString == name)
}

opaque type AuctionImageVariantId = UUID
object AuctionImageVariantId extends Opaque[AuctionImageVariantId, UUID]

final case class AuctionImage(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  fileName: String,
  contentType: `Content-Type`,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant],
  width: Option[Width],
  height: Option[Height],
  format: Option[ImageFormat],
  analyzedAt: Option[Instant])

object AuctionImage {
  def newlyAttached(
    id: AuctionImageId,
    auctionId: AuctionId,
    storageKey: StorageKey,
    fileName: String,
    contentType: `Content-Type`,
    sizeBytes: SizeBytes,
    position: ImagePosition,
    uploadedAt: Instant
  ): AuctionImage = AuctionImage(
    id = id,
    auctionId = auctionId,
    storageKey = storageKey,
    fileName = fileName,
    contentType = contentType,
    sizeBytes = sizeBytes,
    position = position,
    uploadedAt = uploadedAt,
    deletedAt = None,
    width = None,
    height = None,
    format = None,
    analyzedAt = None
  )
}

final case class AuctionImageVariant(
  id: AuctionImageVariantId,
  auctionImageId: AuctionImageId,
  spec: VariantSpec,
  storageKey: StorageKey,
  width: Width,
  height: Height,
  format: ImageFormat,
  generatedAt: Instant)
