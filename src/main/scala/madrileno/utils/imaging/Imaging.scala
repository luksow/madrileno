package madrileno.utils.imaging

import cats.effect.IO
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.file.FileTypeDirectory
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.angles.Degrees
import com.sksamuel.scrimage.nio.{GifWriter, ImageWriter, JpegWriter, PngWriter}
import scodec.bits.ByteVector

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Try

object Imaging {

  def info(bytes: ByteVector): IO[Option[ImageInfo]] = IO.blocking(infoBlocking(bytes))

  def readExif(bytes: ByteVector): IO[Exif] = IO.blocking {
    val tags = Try(ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes.toArray)))
      .map(metadata => metadata.getDirectoriesOfType(classOf[ExifDirectoryBase]).asScala.flatMap(_.getTags.asScala))
      .getOrElse(Iterable.empty)
      .map(t => t.getTagName -> Option(t.getDescription).getOrElse(""))
      .toMap
    Exif(tags)
  }

  def convert(bytes: ByteVector, output: ImageFormat): IO[ByteVector] = transform(bytes, output)(identity)

  def resize(
    bytes: ByteVector,
    maxDimension: Int,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    val longest = math.max(image.width, image.height)
    if (longest <= maxDimension) image
    else if (image.width >= image.height) image.scaleToWidth(maxDimension)
    else image.scaleToHeight(maxDimension)
  }

  def cover(
    bytes: ByteVector,
    width: Width,
    height: Height,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    image.cover(width.unwrap, height.unwrap)
  }

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

  def rotate(
    bytes: ByteVector,
    degrees: Int,
    output: ImageFormat
  ): IO[ByteVector] = transform(bytes, output) { image =>
    val normalized = ((degrees % 360) + 360) % 360
    normalized match {
      case 0   => image
      case 90  => image.rotateRight()
      case 180 => image.rotateRight().rotateRight()
      case 270 => image.rotateLeft()
      case _   => image.rotate(new Degrees(normalized))
    }
  }

  def stripMetadata(bytes: ByteVector, output: ImageFormat): IO[ByteVector] = convert(bytes, output)

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
        val image   = ImmutableImage.loader().fromBytes(bytes.toArray)
        val hasExif = !metadata.getDirectoriesOfType(classOf[ExifDirectoryBase]).isEmpty
        ImageInfo(dimensions = ImageDimensions(Width(image.width), Height(image.height)), format = fmt, hasExif = hasExif, sizeBytes = bytes.size)
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
}
