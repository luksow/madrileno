# Imaging

`madrileno.utils.imaging` is a thin, IO-effected wrapper over [scrimage](https://github.com/sksamuel/scrimage) and [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) for the image operations a typical app actually needs: read dimensions, transform, sniff metadata, strip metadata. Everything works on `scodec.bits.ByteVector` in and out — no streaming, no temp files, no async backends.

## What you get

```
src/main/scala/madrileno/utils/imaging/
  ImageInfo.scala   # Width, Height, ImageDimensions, ImageFormat, Orientation, ImageInfo, Exif
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

  def applyOrientation(bytes: ByteVector, output: ImageFormat): IO[ByteVector]
  def stripMetadata(bytes: ByteVector, output: ImageFormat): IO[ByteVector]
}

enum ImageFormat { case Jpeg, Png, Gif }

opaque type Width  = Int   // > 0
opaque type Height = Int   // > 0
final case class ImageDimensions(width: Width, height: Height)

enum Orientation(val tag: Int) { /* 1..8 per EXIF spec */ }

final case class ImageInfo(
  dimensions: ImageDimensions,
  format: ImageFormat,
  orientation: Orientation,
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
Decodes format / dimensions / EXIF presence / orientation. Returns `None` for non-images and for any input format outside the supported three (JPEG / PNG / GIF). Format detection uses metadata-extractor's `FileTypeDirectory`; dimensions come from scrimage's image loader.

### `readExif(bytes)`
Returns an `Exif` wrapper around the flat `tag-name → human-readable-value` map, scoped to EXIF-related directories only (so PNG IHDR / JPEG segment metadata don't leak in). `Exif.Empty` when there's no EXIF block / unsupported format / parse failure.

### `convert(bytes, output)`
Re-encode in the requested output format. Functionally identical to `stripMetadata` — both decode to pixels and re-encode, dropping all EXIF / IPTC / XMP as a side effect.

### `resize(bytes, maxDimension, output)`
Scale so the longest edge is at most `maxDimension`, preserving aspect ratio. For a 200×100 input with `maxDimension=100`, outputs 100×50.

### `cover(bytes, width, height, output)`
Center-crop fill to exact `width × height`. Scales-and-crops so the result fills the target dimensions exactly. The standard "thumbnail" operation.

### `crop(bytes, x, y, width, height, output)`
Explicit pixel-coordinate crop. Useful for user-driven cropping (avatar editor sends `x/y/w/h`).

### `rotate(bytes, degrees, output)`
Rotate clockwise by `degrees`. Quarter-turns (`0`, `90`, `180`, `270`) and any normalized multiple thereof use scrimage's lossless `rotateLeft` / `rotateRight`. Other angles re-sample with a white background. `Int` because real-world rotation is always integer degrees (EXIF orientation, user "rotate 90°" buttons).

### `applyOrientation(bytes, output)`
Read the EXIF Orientation tag (1–8) and apply the matching combination of flips and rotations so the pixels are in upright `Normal` form. Most phones write photos with sensor-native orientation + an EXIF tag describing how to display; browsers historically ignored the tag, so storing already-applied bytes avoids the "image displays sideways" UX issue.

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

`ImagingSpec` generates fixtures at runtime (`ImmutableImage.filled(w, h, color)` → encode → bytes). 15 cases covering every operation's happy path plus negatives for non-image input. No checked-in binary fixtures — fast and reproducible.

If you need to test EXIF *content* (not just presence), check in a small fixture file and feed it via `Files.readAllBytes`. scrimage's writer drops EXIF on re-encode, so you can't generate one in-process.

## Performance notes

- Every operation decodes and re-encodes — there's no "rotate without re-encoding" fast path. For lossless EXIF-only rotation you'd need a JPEG-aware library (`libjpeg-turbo`'s `jpegtran`, or `org.apache.sanselan`).
- `IO.blocking` puts each call on the cats-effect blocking pool. Don't run hundreds of these concurrently against a busy server — bound your variant generation with a semaphore or a fixed-concurrency stream.
- The buffer cost is bounded by `http.max-request-size` (10 MiB default). Larger files would need a streamed pipeline (`javax.imageio.ImageReader` with a custom InputStream); not in scope for this template.
