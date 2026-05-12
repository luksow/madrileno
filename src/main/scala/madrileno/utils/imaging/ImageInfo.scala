package madrileno.utils.imaging

import pl.iterators.kebs.opaque.Opaque

opaque type Width = Int
object Width extends Opaque[Width, Int] {
  override def validate(value: Int): Either[String, Width] =
    if (value > 0) Right(value) else Left("Width must be positive")
}

opaque type Height = Int
object Height extends Opaque[Height, Int] {
  override def validate(value: Int): Either[String, Height] =
    if (value > 0) Right(value) else Left("Height must be positive")
}

final case class ImageDimensions(width: Width, height: Height)

enum ImageFormat {
  case Jpeg, Png, Gif
}

// `dimensions` are display dims — scrimage applies the EXIF Orientation when it decodes a JPEG.
final case class ImageInfo(
  dimensions: ImageDimensions,
  format: ImageFormat,
  hasExif: Boolean,
  sizeBytes: Long)

final case class Exif(tags: Map[String, String]) {
  def isEmpty: Boolean                  = tags.isEmpty
  def nonEmpty: Boolean                 = tags.nonEmpty
  def get(name: String): Option[String] = tags.get(name)
}

object Exif {
  val Empty: Exif = Exif(Map.empty)
}
