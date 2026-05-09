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

opaque type VariantLabel = String
object VariantLabel extends Opaque[VariantLabel, String] {
  private val pattern = "^[a-z0-9][a-z0-9-]*$".r

  override def validate(value: String): Either[String, VariantLabel] =
    if (pattern.matches(value)) Right(value)
    else Left("VariantLabel must be lower-case alphanumeric/dash, starting with alphanumeric")
}

enum VariantSpec(val label: VariantLabel, val format: ImageFormat) {
  case Thumb  extends VariantSpec(VariantLabel("thumb"), ImageFormat.Jpeg)
  case Medium extends VariantSpec(VariantLabel("medium"), ImageFormat.Jpeg)
}

object VariantSpec {
  val All: List[VariantSpec]                            = values.toList
  def byLabel(label: VariantLabel): Option[VariantSpec] = All.find(_.label == label)
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
  deletedAt: Option[Instant],
  width: Option[Width],
  height: Option[Height],
  format: Option[ImageFormat],
  analyzedAt: Option[Instant])

final case class AuctionImageVariant(
  auctionImageId: AuctionImageId,
  label: VariantLabel,
  storageKey: StorageKey,
  width: Width,
  height: Height,
  format: ImageFormat,
  generatedAt: Instant)
