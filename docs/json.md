# JSON

JSON encoding and decoding is circe, with kebs adapters that recognize opaque types and Scala 3 enums automatically. The single `JsonProtocol` trait pulls everything in; importing it (or extending it via `BaseRouter`) is enough to make `derives Encoder.AsObject, Decoder` work on most case classes you'll write.

## What `JsonProtocol` brings in

```scala
trait JsonProtocol
    extends CirceEntityDecoder      // http4s ↔ circe entity codecs
    with CirceEntityEncoder
    with KebsEnum                   // Scala 3 enum unmarshallers
    with KebsCirceEnums             // circe codecs for Scala 3 enums
    with TimeInstances              // java.time.{Instant, LocalDate, …}
    with UtilInstances              // java.util.{UUID, Currency, …}
    with NetInstances {             // java.net.URI

  export kebs.circe.flatDecoder, flatEncoder              // opaque-type codecs
  export kebs.circe.instanceConverterEncoder, instanceConverterDecoder

  type Encoder[A]  = io.circe.Encoder[A]
  val  Encoder     = io.circe.Encoder
  type Decoder[A]  = io.circe.Decoder[A]
  val  Decoder     = io.circe.Decoder
}
```

The `Encoder`/`Decoder` re-exports mean you write `derives Encoder.AsObject, Decoder` on a case class without an `import io.circe.…` line at the top of every DTO file.

The `flatEncoder` / `flatDecoder` exports are kebs derivations for opaque types: an `opaque type Foo = String` (declared via `kebs.opaque.Opaque`) gets a JSON codec that produces a bare string, not a `{"value": "…"}` object.

## Routers and DTOs

Routers extend `BaseRouter`, which extends `JsonProtocol`, so codec derivations and entity directives are in scope:

```scala
final case class CreateAuctionRequest(
  wineName: WineName,
  vintage: Option[Vintage],
  startingPrice: Price,
  …
) derives Decoder, Encoder.AsObject

(post & path("auctions") & entity(as[CreateAuctionRequest])) { request => … }
```

Standalone files (e.g. `madrileno.auction.routers.dto`) need an explicit import:

```scala
import madrileno.utils.json.JsonProtocol.*

final case class AuctionDto(…) derives Encoder.AsObject, Decoder
```

`Encoder.AsObject` is required when the encoder needs to produce a JSON object rather than a primitive — circe distinguishes the two in its derivation. For DTOs with named fields, always use `AsObject`.

## Opaque types

The repository ships kebs `Opaque[T, A]` for declaring an opaque-type wrapper around a value. Once you've declared one, its JSON codec is automatic:

```scala
opaque type Width = Int
object Width extends Opaque[Width, Int] {
  override def validate(value: Int): Either[String, Width] =
    if (value > 0) Right(value) else Left("Width must be positive")
}
```

`Width(100)` JSON-encodes as `100`, not `{"value": 100}`. Decoding goes through `validate`, so a JSON value of `0` (or any non-positive number) produces a decoding error with the validation message ("Width must be positive"). No additional imports needed beyond `JsonProtocol`.

The same applies to `kebs` enums and `kebs.opaque` types, including chained ones like `opaque type Foo = OtherOpaque`.

## Scala 3 enums

`enum X { case A, B, C }` decodes by case name and encodes by `.toString`. So `ImageFormat.Jpeg` becomes `"Jpeg"` in JSON. If you need a different surface representation (e.g., lowercase), provide an explicit codec.

## When to write a custom codec

Most cases don't need one. Three that do:

1. **External API shapes you don't control** — write a custom decoder that accepts the upstream format and maps to your domain types. See `VivinoGateway` for the worked example.
2. **Field renaming or omission at the wire boundary** — circe's `Encoder.AsObject.derived` uses field names verbatim. If your wire contract differs from your case class, write the encoder by hand or use circe's `Codec.AsObject.derivedConfigured` with a `Configuration` that maps names.
3. **Discriminator-shaped sum types where the default case-name discriminator isn't right** — provide an explicit `Codec` using `Codec.from(decoder, encoder)` over a discriminator string field.

Custom codecs go on the companion of whichever type *uses* them, not on the domain type itself — keeping the domain type free of wire-format concerns. From `AuctionImageService.scala`, the `VariantSpec` enum is encoded as a string only for the scheduler payload, so its codec lives on `GenerateVariantPayload`:

```scala
object GenerateVariantPayload {
  given Encoder[VariantSpec] = Encoder.encodeString.contramap(_.toString)
  given Decoder[VariantSpec] = Decoder.decodeString.emap(s =>
    VariantSpec.byName(s).toRight(s"Unknown VariantSpec: $s")
  )
}
```

## Where the codecs live

- **Domain types' codecs live with their consumer.** `AuctionImage`'s domain type doesn't carry a circe codec — it's encoded as `AuctionImageDto` at the route layer, where the codec is derived. Don't put `derives Encoder.AsObject, Decoder` on a domain case class unless the domain type itself is what crosses the wire.
- **Scheduler payload codecs live on the payload type.** A `OneTimeTask[T]` requires an `Encoder[T]` / `Decoder[T]` for the `TaskDescriptor[T]` constructor. Define them on the payload's companion if the type is service-internal, or import them from a shared module if shared.
- **The big circe `import io.circe.{Codec, Decoder, Encoder}` line** is fine inside a service file that needs explicit codec construction. Don't sprinkle FQNs (`io.circe.Codec.AsObject`) inline.

## Import cheatsheet

```scala
// In a Router (extends BaseRouter): nothing to import — JsonProtocol is mixed in.

// In a DTO file: this brings in Encoder/Decoder type aliases plus given derivations.
import madrileno.utils.json.JsonProtocol.*

// In a Service that defines a scheduler payload: import the type aliases AND the
// circe Codec type for `derives Codec.AsObject` syntax.
import io.circe.{Codec, Decoder, Encoder}
import madrileno.utils.json.JsonProtocol.given
```

The `JsonProtocol.given` form imports only the implicit instances (kebs derivations, time/util/net instances), without bringing in the `Encoder`/`Decoder` type aliases — useful when you've already imported them from `io.circe` directly.

## Testing JSON

DTOs with `derives Encoder.AsObject, Decoder` round-trip automatically. If you want to pin the on-the-wire shape (for example because an external client depends on a specific field order or naming), write an explicit string-comparison test against a representative example. The codebase doesn't do this everywhere — relying on the OpenAPI spec generated by baklava is the more common discipline. See [http.md](http.md).
