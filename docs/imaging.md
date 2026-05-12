# Imaging

`madrileno.utils.imaging` is a thin, IO-effected wrapper over [scrimage](https://github.com/sksamuel/scrimage) and [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) for the image operations a typical app actually needs: read dimensions, transform, sniff metadata, strip metadata. Everything works on `scodec.bits.ByteVector` in and out — no streaming, no temp files, no async backends.

## What you get

```
src/main/scala/madrileno/utils/imaging/
  ImageInfo.scala   # Width, Height, ImageDimensions, ImageFormat, ImageInfo, Exif
  Imaging.scala     # the operations
```

## API

```scala
object Imaging {
  def info(bytes: ByteVector): IO[Option[ImageInfo]]
  def readExif(bytes: ByteVector): IO[Exif]

  def convert(bytes: ByteVector, output: ImageFormat): IO[ByteVector]
  def resize(bytes: ByteVector, maxDimension: Int, output: ImageFormat): IO[ByteVector]
  def cover(bytes: ByteVector, width: Width, height: Height, output: ImageFormat): IO[ByteVector]
  def crop(bytes: ByteVector, x: Int, y: Int, width: Width, height: Height, output: ImageFormat): IO[ByteVector]
  def rotate(bytes: ByteVector, degrees: Int, output: ImageFormat): IO[ByteVector]
  def stripMetadata(bytes: ByteVector, output: ImageFormat): IO[ByteVector]
}

enum ImageFormat { case Jpeg, Png, Gif }

opaque type Width  = Int   // > 0
opaque type Height = Int   // > 0
final case class ImageDimensions(width: Width, height: Height)

final case class ImageInfo(
  dimensions: ImageDimensions,   // display dims — scrimage applies EXIF orientation on decode
  format: ImageFormat,
  hasExif: Boolean,
  sizeBytes: Long
)

final case class Exif(tags: Map[String, String]) {
  def isEmpty: Boolean
  def get(name: String): Option[String]
}
object Exif { val Empty: Exif }
```

All operations are wrapped in `IO.blocking` (CPU-heavy under the hood). Pure object — tests use real bytes, not a fake.

## Operations

### `info(bytes)`
Decodes format / dimensions / EXIF presence. Returns `None` for non-images and for any input format outside the supported three (JPEG / PNG / GIF). Format detection uses metadata-extractor's `FileTypeDirectory`; dimensions come from scrimage's image loader — which **applies the EXIF Orientation tag when it decodes a JPEG**, so the dimensions (and the pixels every operation below sees) are the *display*, upright orientation, not the sensor-native one. If you need the raw tag itself, read it from `readExif` (`"Orientation" → "Rotate 90 CW"` etc.).

### `readExif(bytes)`
Returns an `Exif` wrapper around the flat `tag-name → human-readable-value` map, scoped to EXIF-related directories only (so PNG IHDR / JPEG segment metadata don't leak in). `Exif.Empty` when there's no EXIF block / unsupported format / parse failure.

### `convert(bytes, output)`
Decode and re-encode in the requested output format. Side effects of the round-trip: the EXIF Orientation is baked into the pixels (scrimage applied it on decode) and all EXIF / IPTC / XMP is dropped. So `convert` is also the "normalize an uploaded photo to upright, metadata-free bytes" operation — functionally identical to `stripMetadata`.

### `resize(bytes, maxDimension, output)`
Scale so the longest edge is at most `maxDimension`, preserving aspect ratio. For a 200×100 input with `maxDimension=100`, outputs 100×50.

### `cover(bytes, width, height, output)`
Center-crop fill to exact `width × height`. Scales-and-crops so the result fills the target dimensions exactly. The standard "thumbnail" operation.

### `crop(bytes, x, y, width, height, output)`
Explicit pixel-coordinate crop. Useful for user-driven cropping (avatar editor sends `x/y/w/h`).

### `rotate(bytes, degrees, output)`
Rotate clockwise by `degrees`. Quarter-turns (`0`, `90`, `180`, `270`) and any normalized multiple thereof use scrimage's lossless `rotateLeft` / `rotateRight`. Other angles re-sample with a white background. `Int` because real-world rotation is always integer degrees (a user "rotate 90°" button). Note this is *explicit* rotation — EXIF orientation is already handled by scrimage on decode; you don't rotate by the tag yourself (see `convert`).

### `stripMetadata(bytes, output)`
Re-encode without metadata. Privacy-safe (location, device serial, etc. gone). Same internal path as `convert`; the separate verb exists so call sites can express intent.

## Format scope

Only **JPEG / PNG / GIF**. scrimage-core 4.x ships `ImageWriter` instances for those three; everything else (BMP, TIFF, WebP, AVIF) requires either a separate scrimage module with native binaries or a hand-rolled `javax.imageio` bridge. For typical web image upload flows this is enough — clients send JPEG/PNG/GIF in 99%+ of cases.

To add another format:

- **BMP / TIFF**: write a small `ImageWriter` adapter that delegates to `javax.imageio.ImageIO.write(image.awt, "bmp"/"tiff", out)`. Add an enum case, wire it in `Imaging.writerFor`. ~15 LOC.
- **WebP**: add `"com.sksamuel.scrimage" % "scrimage-webp" % scrimageV` and another enum case. The native binaries cover Linux x64, macOS x64+ARM64, Windows x64 — Linux ARM64 / Alpine are not in the bundle, so you'll hit `UnsatisfiedLinkError` at runtime on those platforms unless you ship a compatible binary yourself.
- **AVIF**: scrimage's AVIF support is rough; expect to write your own.

## Library choice

- **`com.sksamuel.scrimage:scrimage-core`** does the pixel work — pure-Java ImageIO under the hood, no native deps. `ImmutableImage` is the value type the operations chain through.
- **`com.drewnoakes:metadata-extractor`** does format sniffing + EXIF parsing. Comes transitively via scrimage, no extra `build.sbt` line needed.

## Testing

`ImagingSpec` generates most fixtures at runtime (`ImmutableImage.filled(w, h, color)` → encode → bytes) — fast and reproducible. The one exception is `src/test/resources/exif-orientation-6.jpg` (a 1200×600 JPEG carrying `Orientation = 6`, so it displays as 600×1200): scrimage's writer drops EXIF on re-encode, so an EXIF-bearing fixture can't be generated in-process and has to be checked in. Use it (via `Files.readAllBytes`) whenever you need to exercise EXIF *content*, not just presence — e.g. asserting that `convert` / the auction-image `analyze` step produces upright bytes.

## Performance notes

- Every operation decodes and re-encodes — there's no "rotate without re-encoding" fast path. For lossless EXIF-only rotation you'd need a JPEG-aware library (`libjpeg-turbo`'s `jpegtran`, or `org.apache.sanselan`).
- `IO.blocking` puts each call on the cats-effect blocking pool. Don't run hundreds of these concurrently against a busy server. The auction-images variant pipeline avoids this by running each `generateVariantTask` on the scheduler — `scheduler.concurrency` (10 by default) is the effective parallelism cap.
- The buffer cost is bounded by `http.max-request-size` (10 MiB default). Larger files would need a streamed pipeline (`javax.imageio.ImageReader` with a custom InputStream); not in scope for this template.
