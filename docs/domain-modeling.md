# Domain modeling

Types are the first line of validation. A `WineName` can't be empty because the constructor refuses to build one from `""`. A `Price` can't be negative because the constructor refuses. By the time domain logic touches a value, the value is already valid — the rest of the code doesn't re-check, doesn't defensively guard, doesn't carry "is this real?" branches.

Three ingredients buy this:

1. **Opaque types** for primitives that mean something specific (`AuctionId`, `WineName`, `Price`).
2. **Enums** for closed sets — domain states (`AuctionStatus`), categorical fields (`WineColor`), operation outcomes (`BidRejection`).
3. **Smart constructors** — methods returning `Either[Error, Domain]` where invariants matter, plain constructors elsewhere.

The result is a domain layer where invalid states are unrepresentable, and where the `case Left(...)` branches in route handlers map directly onto the named domain rejections.

## Opaque types

Scala 3 opaque types are zero-overhead wrappers. At runtime they're the underlying primitive; at compile time they're a distinct nominal type. You can't pass an `AuctionId` where a `UserId` is expected — even though both are UUIDs underneath.

The project uses kebs's `Opaque` helper to remove the boilerplate:

```scala
opaque type AuctionId = UUID
object AuctionId extends Opaque[AuctionId, UUID]
```

That's the whole declaration. The companion gets you `AuctionId(uuid)` for construction, `id.unwrap` for the underlying value, and `Eq` / typeclass derivations downstream (kebs hooks into circe, baklava, skunk, etc.).

For values that need invariants, override `validate`:

```scala
opaque type WineName = String
object WineName extends Opaque[WineName, String] {
  override def validate(value: String): Either[String, WineName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Wine name must not be empty")
  }
}
```

`WineName.validate("Château Margaux")` returns `Right(WineName("Château Margaux"))`. `WineName.validate("")` returns `Left("Wine name must not be empty")`. The error string surfaces in:
- HTTP layer: kebs's circe codecs use `validate` automatically; bad JSON becomes a 400 with the error in the body.
- Domain layer: when you have a raw value and need to lift it, `WineName.validate(s).getOrElse(...)`.

When you don't need invariants — IDs, lookup keys — skip the `validate` override:

```scala
opaque type UserId = UUID
object UserId extends Opaque[UserId, UUID]
```

`Opaque[UserId, UUID]`'s default `validate` is the identity. `UserId(uuid)` always succeeds. The opaqueness is the only thing buying value here, and that's enough — it stops `userId` and `auctionId` from being interchangeable in argument lists, and it gives the type-driven JSON / DB / OpenAPI codec generators something to dispatch on.

A non-exhaustive list from the codebase, by category:

| Kind                | Examples                                                                        |
| ------------------- | ------------------------------------------------------------------------------- |
| IDs (no validation) | `AuctionId`, `BidId`, `UserId`, `AuctionImageId`                                |
| Validated text      | `WineName`, `Region`, `Appellation`, `ProducerName`, `Description`, `EmailAddress`, `FullName` |
| Validated numerics  | `Price` (> 0), `Rating` (0–5), `Vintage` (1800–2100), `BottleCount` (≥ 1), `RatingsCount` (≥ 0), `SizeBytes` (≥ 0), `ImagePosition` (≥ 0) |

Givens like `Ordering[Price]` go on the companion when the domain needs them — that's where the type-class search looks.

## Behavior belongs on the value

Opaque types are value objects in the DDD sense — values with behavior, not anaemic wrappers. Operations that are intrinsically "about this value" go on the value itself, as extension methods on the companion:

```scala
opaque type StorageKey = String
object StorageKey extends Opaque[StorageKey, String] {
  override def validate(value: String): Either[String, StorageKey] = …

  extension (key: StorageKey) def render: String = key.unwrap
}

opaque type SignedUrlTtl = FiniteDuration
object SignedUrlTtl extends Opaque[SignedUrlTtl, FiniteDuration] {
  override def validate(value: FiniteDuration): Either[String, SignedUrlTtl] = …

  extension (ttl: SignedUrlTtl) def asJavaDuration: Duration =
    Duration.ofSeconds(ttl.unwrap.toSeconds)
}

opaque type CronExpression = CronExpr
object CronExpression {
  def parse(expression: String): Either[String, CronExpression] = …

  extension (cron: CronExpression) {
    def nextFrom(now: Instant, zoneId: ZoneId = ZoneOffset.UTC): Option[Instant] = …
  }
}
```

The pattern: `extension (x: T) def foo: U = ...` on the companion, alongside `validate`. Same for typeclass givens — `given Ordering[Price]` lives on the companion next to whatever else `Price` knows how to do.

If a `Price + Price` operation makes sense in the domain, put it there. Same for `WineName.normalizedForSearch`, `Vintage.decade`, `EmailAddress.localPart`. These are properties of the value; they don't need anything outside the value to compute.

What *doesn't* go on the value: operations that need other aggregates, repository access, time-of-day, or external state. `Price.affordableBy(user)` is wrong — that needs the user's balance, the auction's currency, today's exchange rate. That's an aggregate or service concern. The rule of thumb: if computing the operation needs only the value itself (and pure arguments), it belongs on the value; if it needs to consult anything else, it belongs higher up.

So the case-class-level operations on `Auction` — `placeBid`, `cancel`, `close` — sit on `Auction`, not on `Price`, because they're about the auction's lifecycle and depend on bids, status, the seller, and the clock:

```scala
final case class Auction(id: AuctionId, …) {
  def placeBid(...): Either[BidRejection, Bid] = …
  def cancel(...): Either[CancellationRejection, Auction] = …
  def close(...): Either[CloseRejection, Auction] = …
}
```

The line is between value-local logic (on the opaque type) and aggregate-level orchestration (on the case class). Both are domain behavior; they live where their data lives.

## Enums for closed sets

Three uses, all in `Auction.scala`:

**Categorical fields** — distinct values that belong together but have no behavior:

```scala
enum WineColor {
  case Red, White, Rose, Orange, Sparkling, Dessert, Fortified
}

enum BottleSize {
  case Half, Standard, Magnum, DoubleMagnum, Jeroboam, Other
}
```

**State machines** — enumerated lifecycle states:

```scala
enum AuctionStatus {
  case Open, Closed, Cancelled
}
```

The transition rules live on the aggregate (`Auction.cancel` only succeeds when `status == Open`), not on the enum. Keep the enum as a label; let the case class enforce which transitions are legal.

**Operation outcomes** — the ADT-per-result-type pattern from [error-handling.md](error-handling.md):

```scala
enum BidRejection {
  case AuctionNotOpen
  case AuctionNotStarted
  case AuctionEnded
  case CannotBidOnOwnAuction
  case AlreadyHighestBidder
  case BidTooLow(currentHighest: Price)
}
```

Each `Either[BidRejection, Bid]` return type maps one-to-one onto the cases. The router pattern-matches:

```scala
case PlaceBidResult.BidPlaced(bid, _)     => Created -> BidDto(bid)
case PlaceBidResult.AlreadyHighestBidder  => error(Conflict, "already-highest-bidder", …)
case PlaceBidResult.BidTooLow(highest)    => error(Conflict, "bid-too-low", s"…$highest")
```

Compiler warns when a new case is added and a router forgets to handle it. The case-with-payload (`BidTooLow(currentHighest: Price)`) carries the data the API needs to render a useful error message.

## Smart constructors

When invariants span multiple fields, put a smart constructor on the companion:

```scala
object Auction {
  def open(
    id: AuctionId,
    sellerId: UserId,
    …,
    startsAt: Instant,
    endsAt: Instant,
    now: Instant
  ): Either[AuctionCreationError, Auction] = {
    if (!endsAt.isAfter(startsAt) || !endsAt.isAfter(now))
      Left(AuctionCreationError.InvalidWindow)
    else
      Right(Auction(…, status = AuctionStatus.Open, createdAt = now, …))
  }
}
```

Two things to notice:

- **Named: `open`, not `apply` or `create`.** The verb says what it does — the auction is being *opened* in the lifecycle. Future `Auction.cloneFrom(...)` or `Auction.fromRow(...)` are different operations on the same type.
- **Pass `now: Instant` in.** Don't reach for `Instant.now()` inside. Time is a dependency; passing it makes the function pure and trivially testable.

Per-step transitions follow the same shape:

```scala
def cancel(requesterId: UserId, now: Instant): Either[CancellationRejection, Auction] = {
  if (requesterId != sellerId)             Left(CancellationRejection.NotOwner)
  else if (status != AuctionStatus.Open)   Left(CancellationRejection.AuctionNotOpen)
  else if (!now.isBefore(endsAt))          Left(CancellationRejection.AuctionEnded)
  else                                     Right(copy(status = AuctionStatus.Cancelled, updatedAt = now))
}
```

Each branch corresponds to one `CancellationRejection` case. Order matters in cascading guards — checking `NotOwner` before `AuctionNotOpen` means a non-owner gets the same response whether the auction is open or not (no information leak about state).

## Views and projections

The persisted entity isn't always the right shape for callers. `AuctionView` is the read-side projection:

```scala
final case class AuctionView(
  auction: Auction,
  currentPrice: Price,
  rating: Option[VivinoRating] = None
) {
  export auction.*
}
```

Two patterns worth copying:

- **Composition over inheritance.** `AuctionView` *contains* an `Auction`; it doesn't extend it. The view is free to add fields (`currentPrice`, `rating`) without touching the persisted shape.
- **`export auction.*` for delegation.** The view's callers can read `view.id`, `view.wineName`, `view.status` directly — `export` re-publishes the contained entity's members on the wrapper. Saves writing 15 forwarders by hand.

Use views when:
- The aggregate's persisted shape and the read API diverge.
- A read response needs joined/derived data (current high bid, derived ratings, computed totals).

Don't use views just to add one method — put the method on the aggregate. Views earn their keep when there's actual *additional state* to carry alongside.

## Events as their own ADT

`AuctionEvent` is a separate enum from any of the rejections:

```scala
enum AuctionEvent derives EventCodec {
  def auctionId: AuctionId
  def at: Instant

  case AuctionCreated(auctionId: AuctionId, wineName: WineName, …, at: Instant)
  case BidPlaced(auctionId: AuctionId, amount: Price, currency: Currency, at: Instant)
  case AuctionCancelled(auctionId: AuctionId, at: Instant)
  case AuctionClosed(auctionId: AuctionId, winningBid: Option[Price], …)
}
```

Three things:

- **Common abstract members.** Every case has `auctionId` and `at`; declaring them on the enum forces every variant to provide them, and lets subscribers read them without pattern-matching. (See [event-bus.md](event-bus.md) for the broader pattern.)
- **Mapped from domain values.** `AuctionEvent.bidPlaced(bid, auction)` is a pure function from the domain types to the event. Chimney handles the structural transform; you write the field renames and the constants. Keeps event construction terse and refactor-safe.
- **Internal, not the public wire format.** The DTO projection happens at the WebSocket / HTTP boundary. Don't ship the internal event shape to clients — see [websockets.md](websockets.md) for the projection pattern.

## Cross-module domain references

`AuctionRepository.findOpen(...)` returns auctions referencing `UserId`s, but the auction module never sees a `User` value. The user module's `UserId` is imported into auction code; the user's `User` case class is not.

The discipline:

- IDs cross module boundaries freely. They're cheap, they're stable, they're the lingua franca.
- Aggregates do not. If the auction module wants user data alongside an auction, it asks the user repository through a typed interface; it doesn't import `User` and field-access into it.

This keeps the module graph shallow. Auction depends on user-the-module-API, not on user-the-internal-data. Refactoring a module's internals doesn't ripple into the modules that consume it.

## Where validation happens

Two layers, both of them concentrated:

- **At the edges.** HTTP request bodies decode through circe, which calls each opaque type's `validate`. Bad input never reaches the service; it returns a 400. Same for JSON config, JSON event payloads, JSON DB columns.
- **Inside smart constructors.** Cross-field invariants that types can't express (`endsAt > startsAt`, `requesterId == sellerId`) live in the named operation methods.

Everywhere else, the code trusts that values are valid. No re-checking inside a service, no defensive null checks, no "what if the price is negative" branches. The types said it can't be, so it isn't.

When you find a `require(...)` deep in service code, ask whether the type system could have kept the bad value out instead. Usually yes.

## Where to look next

- [error-handling.md](error-handling.md) — the result-ADT pattern in service methods.
- [sealed-monad.md](sealed-monad.md) — composing `Either[Rejection, _]` operations cleanly.
- [json.md](json.md) — circe codecs for opaque types and enums.
- [database.md](database.md) — opaque types on the persistence side; how kebs hooks into Skunk codecs.
- [event-bus.md](event-bus.md) — the events ADT pattern with `derives EventCodec`.
