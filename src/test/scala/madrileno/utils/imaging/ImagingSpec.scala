package madrileno.utils.imaging

import cats.effect.testing.scalatest.AsyncIOSpec
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.{GifWriter, JpegWriter, PngWriter}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import scodec.bits.ByteVector

import java.awt.Color

class ImagingSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  private def fixture(
    width: Int,
    height: Int,
    color: Color,
    format: ImageFormat
  ): ByteVector = {
    val image = ImmutableImage.filled(width, height, color)
    val writer = format match {
      case ImageFormat.Jpeg => JpegWriter.Default
      case ImageFormat.Png  => PngWriter.MaxCompression
      case ImageFormat.Gif  => GifWriter.Default
    }
    ByteVector(image.bytes(writer))
  }

  private val landscapeJpeg = fixture(200, 100, Color.RED, ImageFormat.Jpeg)
  private val squarePng     = fixture(50, 50, Color.GREEN, ImageFormat.Png)
  private val tinyGif       = fixture(10, 10, Color.BLUE, ImageFormat.Gif)
  private val notAnImage    = ByteVector("not an image at all".getBytes("UTF-8"))

  "Imaging.info" should {
    "return dimensions, format, and orientation for a JPEG" in {
      Imaging.info(landscapeJpeg).map { result =>
        result shouldBe defined
        val info = result.get
        info.dimensions shouldBe ImageDimensions(Width(200), Height(100))
        info.format shouldBe ImageFormat.Jpeg
        info.orientation shouldBe Orientation.Normal
        info.sizeBytes shouldBe landscapeJpeg.size
      }
    }

    "recognize PNG and GIF" in {
      for {
        png <- Imaging.info(squarePng)
        gif <- Imaging.info(tinyGif)
      } yield {
        png.map(_.format) shouldBe Some(ImageFormat.Png)
        gif.map(_.format) shouldBe Some(ImageFormat.Gif)
      }
    }

    "return None for non-image bytes" in {
      Imaging.info(notAnImage).map(_ shouldBe None)
    }
  }

  "Imaging.readExif" should {
    "return Exif.Empty for an image with no EXIF segment" in {
      Imaging.readExif(squarePng).map(_ shouldBe Exif.Empty)
    }

    "return Exif.Empty for non-image bytes" in {
      Imaging.readExif(notAnImage).map(_ shouldBe Exif.Empty)
    }
  }

  "Imaging.convert" should {
    "decode JPEG and re-encode as PNG" in {
      for {
        png  <- Imaging.convert(landscapeJpeg, ImageFormat.Png)
        info <- Imaging.info(png)
      } yield info.map(_.format) shouldBe Some(ImageFormat.Png)
    }
  }

  "Imaging.resize" should {
    "scale long edge to maxDimension preserving aspect" in {
      for {
        resized <- Imaging.resize(landscapeJpeg, 100, ImageFormat.Jpeg)
        info    <- Imaging.info(resized)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(100), Height(50)))
    }

    "not upscale when the image is already within bounds" in {
      for {
        resized <- Imaging.resize(squarePng, 200, ImageFormat.Png)
        info    <- Imaging.info(resized)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(50), Height(50)))
    }
  }

  "Imaging.cover" should {
    "produce exactly the requested dimensions" in {
      for {
        thumb <- Imaging.cover(landscapeJpeg, Width(64), Height(64), ImageFormat.Jpeg)
        info  <- Imaging.info(thumb)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(64), Height(64)))
    }
  }

  "Imaging.crop" should {
    "extract a sub-region with the requested dimensions" in {
      for {
        cropped <- Imaging.crop(landscapeJpeg, x = 10, y = 10, Width(80), Height(40), ImageFormat.Jpeg)
        info    <- Imaging.info(cropped)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(80), Height(40)))
    }
  }

  "Imaging.rotate" should {
    "swap width and height on a 90° rotation" in {
      for {
        rotated <- Imaging.rotate(landscapeJpeg, 90, ImageFormat.Jpeg)
        info    <- Imaging.info(rotated)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(100), Height(200)))
    }

    "preserve dimensions on a 180° rotation" in {
      for {
        rotated <- Imaging.rotate(landscapeJpeg, 180, ImageFormat.Jpeg)
        info    <- Imaging.info(rotated)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(200), Height(100)))
    }

    "no-op on 0°" in {
      for {
        rotated <- Imaging.rotate(landscapeJpeg, 0, ImageFormat.Jpeg)
        info    <- Imaging.info(rotated)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(200), Height(100)))
    }
  }

  "Imaging.applyOrientation" should {
    "be a no-op when the image has no EXIF orientation tag" in {
      for {
        oriented <- Imaging.applyOrientation(landscapeJpeg, ImageFormat.Jpeg)
        info     <- Imaging.info(oriented)
      } yield info.map(_.dimensions) shouldBe Some(ImageDimensions(Width(200), Height(100)))
    }
  }

  "Imaging.stripMetadata" should {
    "produce an image with no EXIF" in {
      for {
        stripped <- Imaging.stripMetadata(landscapeJpeg, ImageFormat.Jpeg)
        info     <- Imaging.info(stripped)
      } yield info.map(_.hasExif) shouldBe Some(false)
    }
  }
}
