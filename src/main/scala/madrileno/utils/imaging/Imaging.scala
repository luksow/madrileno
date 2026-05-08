package madrileno.utils.imaging

import cats.effect.IO
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory}
import com.drew.metadata.file.FileTypeDirectory
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.angles.Degrees
import com.sksamuel.scrimage.nio.{GifWriter, ImageWriter, JpegWriter, PngWriter}
import scodec.bits.ByteVector

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Try

object Imaging {

  /** Returns dimensions, format, EXIF presence, and orientation for any of the three supported input formats (JPEG / PNG / GIF). `None` when the
    * bytes don't parse as a recognized image.
    */
  def info(bytes: ByteVector): IO[Option[ImageInfo]] = IO.blocking(infoBlocking(bytes))

  /** Read EXIF tags as a flat string→string map. Empty if no EXIF segment / unsupported format. */
  def readExif(bytes: ByteVector): IO[Map[String, String]] = IO.blocking {
    Try(ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes.toArray)))
      .map(metadata => metadata.getDirectoriesOfType(classOf[ExifDirectoryBase]).asScala.flatMap(_.getTags.asScala))
      .getOrElse(Iterable.empty)
      .map(t => t.getTagName -> Option(t.getDescription).getOrElse(""))
      .toMap
  }

  /** Re-encode in the requested output format. Drops all metadata (EXIF / IPTC / XMP) as a side effect of decoding to pixels and re-encoding. */
  def convert(bytes: ByteVector, output: ImageFormat): IO[ByteVector] = transform(bytes, output)(identity)

  /** Resize so the longest edge is `maxDimension`, preserving aspect. Re-encodes (drops metadata). */
  def resize(
    bytes: ByteVector,
    maxDimension: Int,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    if (image.width >= image.height) image.scaleToWidth(maxDimension)
    else image.scaleToHeight(maxDimension)
  }

  /** Center-crop fill to exact dimensions: scales-and-crops so the result is exactly `width × height` pixels. */
  def cover(
    bytes: ByteVector,
    width: Width,
    height: Height,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    image.cover(width.unwrap, height.unwrap)
  }

  /** Explicit pixel-coordinate crop. */
  def crop(
    bytes: ByteVector,
    x: Int,
    y: Int,
    width: Width,
    height: Height,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    image.subimage(x, y, width.unwrap, height.unwrap)
  }

  /** Rotate by an arbitrary number of degrees (clockwise). Quarter-turns (90/180/270) use lossless rotations; other angles re-sample with a white
    * background.
    */
  def rotate(
    bytes: ByteVector,
    degrees: Double,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    val normalized = ((degrees % 360) + 360) % 360
    normalized.toInt match {
      case 0   => image
      case 90  => image.rotateRight()
      case 180 => image.rotateRight().rotateRight()
      case 270 => image.rotateLeft()
      case _   => image.rotate(new Degrees(normalized.toInt))
    }
  }

  /** Read the EXIF Orientation tag and apply the matching flip + rotation so the pixels are in upright "1=Normal" form. */
  def applyOrientation(bytes: ByteVector, output: ImageFormat): IO[ByteVector] = transform(bytes, output) { image =>
    readOrientationFromBytes(bytes) match {
      case Orientation.Normal           => image
      case Orientation.MirrorH          => image.flipX()
      case Orientation.Rotate180        => image.rotateRight().rotateRight()
      case Orientation.MirrorV          => image.flipY()
      case Orientation.MirrorHRotate270 => image.flipX().rotateLeft()
      case Orientation.Rotate90         => image.rotateRight()
      case Orientation.MirrorHRotate90  => image.flipX().rotateRight()
      case Orientation.Rotate270        => image.rotateLeft()
    }
  }

  /** Re-encode without any metadata. Functionally identical to `convert` — separate verb for callers that explicitly want metadata gone (privacy,
    * archival).
    */
  def stripMetadata(bytes: ByteVector, output: ImageFormat): IO[ByteVector] = convert(bytes, output)

  // --- internals ---

  private def transform(bytes: ByteVector, output: ImageFormat)(f: ImmutableImage => ImmutableImage): IO[ByteVector] = IO.blocking {
    val image  = ImmutableImage.loader().fromBytes(bytes.toArray)
    val out    = f(image)
    val writer = writerFor(output)
    ByteVector(out.bytes(writer))
  }

  private def writerFor(format: ImageFormat): ImageWriter = format match {
    case ImageFormat.Jpeg => JpegWriter.Default
    case ImageFormat.Png  => PngWriter.MaxCompression
    case ImageFormat.Gif  => GifWriter.Default
  }

  private def infoBlocking(bytes: ByteVector): Option[ImageInfo] =
    Try {
      val metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes.toArray))
      val format   = formatFrom(metadata)
      format.map { fmt =>
        val image       = ImmutableImage.loader().fromBytes(bytes.toArray)
        val orientation = orientationFrom(metadata)
        val hasExif     = Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory])).isDefined
        ImageInfo(
          dimensions = ImageDimensions(Width(image.width), Height(image.height)),
          format = fmt,
          orientation = orientation,
          hasExif = hasExif,
          sizeBytes = bytes.size
        )
      }
    }.toOption.flatten

  private def formatFrom(metadata: Metadata): Option[ImageFormat] =
    Option(metadata.getFirstDirectoryOfType(classOf[FileTypeDirectory]))
      .flatMap(d => Option(d.getString(FileTypeDirectory.TAG_DETECTED_FILE_TYPE_NAME)))
      .flatMap {
        case "JPEG" => Some(ImageFormat.Jpeg)
        case "PNG"  => Some(ImageFormat.Png)
        case "GIF"  => Some(ImageFormat.Gif)
        case _      => None
      }

  private def orientationFrom(metadata: Metadata): Orientation =
    Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      .flatMap(d => Option(d.getInteger(ExifDirectoryBase.TAG_ORIENTATION)))
      .flatMap(tag => Orientation.fromTag(tag.intValue))
      .getOrElse(Orientation.Normal)

  private def readOrientationFromBytes(bytes: ByteVector): Orientation =
    Try(ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes.toArray))).map(orientationFrom).getOrElse(Orientation.Normal)
}
