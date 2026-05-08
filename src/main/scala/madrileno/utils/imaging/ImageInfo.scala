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

enum Orientation(val tag: Int) {
  case Normal           extends Orientation(1)
  case MirrorH          extends Orientation(2)
  case Rotate180        extends Orientation(3)
  case MirrorV          extends Orientation(4)
  case MirrorHRotate270 extends Orientation(5)
  case Rotate90         extends Orientation(6)
  case MirrorHRotate90  extends Orientation(7)
  case Rotate270        extends Orientation(8)
}

object Orientation {
  def fromTag(tag: Int): Option[Orientation] = values.find(_.tag == tag)
}

final case class ImageInfo(
  dimensions: ImageDimensions,
  format: ImageFormat,
  orientation: Orientation,
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
