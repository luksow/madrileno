# Sealed monad

`pl.iterators.sealedmonad` is the project's idiom for service methods that return a result ADT — one method, one `Result` enum, success is one case, every short-circuit reason is another case. It turns "five different ways this can go wrong, each with its own response" into a flat for-comprehension where each step says "here's how to fail, otherwise carry on."

It's a thin layer. There's no monad transformer to learn, no error channel to thread, no `EitherT` boilerplate. Just `valueOr`, `ensure`, `seal`, and `run`.

## The problem it solves

Take `placeBid`. It can short-circuit five ways:

- The auction doesn't exist → `AuctionNotFound`.
- The bidder is the seller → `CannotBidOnOwnAuction`.
- The auction isn't open / hasn't started / has ended → corresponding cases.
- The bid isn't higher than the current top → `BidTooLow(currentHighest)`.
- And on the happy path: persist a `Bid`, notify the previous bidder, return `BidPlaced`.

Without sealed-monad, you'd write nested pattern matches or chains of `flatMap` that bottom out in `IO[PlaceBidResult]`, with each result-producing branch repeating the wrap. Or you'd reach for `EitherT[IO, PlaceBidResult, A]`, where success and "everything else that's also a valid response" sit on opposite sides of an asymmetry that doesn't match the domain.

`Sealed` flips it: every step is "produce a `PlaceBidResult` or move on." The result ADT is one type, both success and not-quite-success cases are first-class, and a for-comprehension reads top to bottom.

## The pattern

`reorderImages` shows the typical shape — three short-circuit reasons, `ensure` doing real work in two different flavors:

```scala
import pl.iterators.sealedmonad.syntax.*

def reorderImages(
  auctionId: AuctionId,
  sellerId: UserId,
  orderedIds: List[AuctionImageId]
): IO[ReorderImagesResult] =
  transactor.inTransaction {
    (for {
      _ <- auctionRepository
             .findForUpdate(auctionId)
             .valueOr[ReorderImagesResult](ReorderImagesResult.AuctionNotFound)
             .ensure(_.sellerId == sellerId, ReorderImagesResult.NotOwner)
      current <- auctionImageRepository.listByAuctionForUpdate(auctionId).seal
      _ <- IO.pure(orderedIds).seal
             .ensure(
               ids => ids.toSet == current.map(_.id).toSet && ids.length == current.length,
               ReorderImagesResult.MismatchedIds
             )
      updates = orderedIds.zipWithIndex.map { case (id, idx) => (id, ImagePosition(idx)) }
      _ <- auctionImageRepository.bulkSetPositions(auctionId, updates).seal
    } yield ReorderImagesResult.Reordered).run
  }
```

Six things happen here:

1. **Wrap the for-comprehension in `(for { … } yield happy).run`** — `run` collapses the sealed pipeline back to `IO[ReorderImagesResult]`.
2. **`.valueOr[R](case)`** — for an `IO[Option[A]]` step: continue with `A` on `Some`, short-circuit the whole comprehension with `case` on `None`. The `[R]` type ascription tells the compiler what result type the comprehension yields; it's a small price for cleaner inference.
3. **`.ensure(predicate, R)` after `.valueOr`** — chains an extra invariant onto the `Some` branch. "Auction not found *or* not yours; otherwise carry on." Reads as one stack of guards.
4. **`.ensure(predicate, R)` after `.seal`** — validates a value already in scope (the request body). `IO.pure(orderedIds).seal.ensure(...)` is the idiom for "the input has to satisfy this; otherwise the answer is `MismatchedIds`."
5. **`.seal`** — for any `IO[A]` step that *can't* short-circuit. Lifts the value into the sealed pipeline and does nothing else. Without `.seal` the for-comprehension would refuse to mix raw `IO[A]` with sealed steps.
6. **`yield ReorderImagesResult.Reordered` then `.run`** — the happy-path case is what the `Sealed` returns when no step short-circuited; `.run` collapses everything back to `IO[ReorderImagesResult]`.

The same shape repeats across the codebase. Once you've read three examples, you can read all of them.

`rethrow[IO]` is the one combinator not in this example — it's used when a domain method itself returns `Either[Rejection, A]`. From `placeBid`:

```scala
bid <- auction
         .placeBid(command.bidderId, command.amount, bidId, now, previousHighest)
         .left.map { /* BidRejection -> PlaceBidResult */ }
         .rethrow[IO]
```

`Right(bid)` continues the comprehension; `Left(result)` short-circuits with that result. Reach for it whenever a pure domain method returns its own `Either[Rejection, A]` and you want to fold the rejection cases into the outer result ADT.

## The combinators

The `import pl.iterators.sealedmonad.syntax.*` brings in the *lifters* — extension methods on `IO[A]`, `IO[Option[A]]`, `IO[Either[L, R]]`, `Either[L, R]`, and bare `A`. Once you're inside a `Sealed[IO, A, R]` value, the operators on `Sealed` itself take over — those don't need any extra import.

### Lift `F[…]` into a sealed step

| Combinator                                   | Input                       | Behavior                                                                                                |
| -------------------------------------------- | --------------------------- | ------------------------------------------------------------------------------------------------------- |
| `.seal`                                      | `IO[A]` or bare `A`         | Lift into the pipeline; never short-circuits. The catch-all when no fold semantics apply.                |
| `.valueOr[R](r)`                             | `IO[Option[A]]`             | `Some(a)` continues; `None` short-circuits with `r`.                                                     |
| `.valueOrF[R](io: IO[R])`                    | `IO[Option[A]]`             | Like `valueOr` but the fallback is effectful — typically logs first, then yields the result.            |
| `.emptyOr[R](f: A => R)`                     | `IO[Option[A]]`             | Inverted `valueOr`: `None` continues with `Unit`; `Some(a)` short-circuits with `f(a)`. Rare.            |
| `.emptyOrF[R](f: A => IO[R])`                | `IO[Option[A]]`             | Effectful flavor of `emptyOr`.                                                                            |
| `.rethrow[F]`                                | `Either[R, A]`              | `Right(a)` continues; `Left(r)` short-circuits. Used when a domain method already returns the right ADT. |
| `.fromEither`                                | `IO[Either[R, A]]`          | Same shape as `rethrow`, lifted from `IO[Either[…]]`.                                                    |
| `.handleError[R](f: L => R)`                 | `IO[Either[L, A]]`          | Continue with `A` on `Right`; map `L` to the result ADT and short-circuit on `Left`.                     |
| `.merge[R](f: Either[A, B] => R)`            | `IO[Either[A, B]]`          | Fold both branches into a single `R`. Useful when both sides need translation.                            |
| `.mergeF[R](f: Either[A, B] => IO[R])`       | `IO[Either[A, B]]`          | Effectful flavor of `merge`.                                                                              |
| `.attempt[B, R](f: A => Either[R, B])`       | `IO[A]`                     | Convert value to `Either` inline; `Right` continues, `Left` short-circuits.                              |
| `.attemptF[B, R](f: A => IO[Either[R, B]])`  | `IO[A]`                     | Effectful flavor of `attempt`.                                                                            |

`valueOrF` is the one to reach for when a missing value should be logged before the pipeline ends. From `AuctionImageService.analyze`:

```scala
image <- transactor
           .inSession(auctionImageRepository.find(imageId))
           .valueOrF[Unit](logger.warn(s"analyze: image $imageId not found, skipping"))
```

The `[Unit]` says "this comprehension yields `Unit`"; the warning logs the miss; the pipeline halts cleanly. No exception, no stack trace, no NotFound to thread upward.

### Gate the pipeline

`ensure` and its variants short-circuit when an invariant doesn't hold. The base `ensure` matches what most rejections need; the rest exist for cases where the result depends on the value, the fallback is effectful, or reading the predicate inverted feels more natural.

| Combinator                                                    | Behavior                                                                                                  |
| ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `.ensure(pred, r)`                                            | Continue if `pred(a)`; otherwise short-circuit with `r`.                                                   |
| `.ensureF(pred, io: IO[R])`                                   | Same, with effectful fallback (logs, runs side effects, then yields `R`).                                  |
| `.ensureNot(pred, r)` / `.ensureNotF(pred, io)`                | Inverse predicate: short-circuit when `pred(a)` *holds*. Reads better when guarding negative conditions.   |
| `.ensureOr(pred, a => r)` / `.ensureOrF(pred, a => io)`        | Short-circuit with a result *derived from* `a`. Use when the rejection carries data (`BidTooLow(highest)`). |
| `.ensureNotOr(pred, a => r)` / `.ensureNotOrF(pred, a => io)`  | Inverted `ensureOr`.                                                                                       |

`ensureOr` is what to reach for when the rejection case carries information: `.ensureOr(_.amount > floor, a => PlaceBidResult.BidTooLow(a.previousFloor))`. Compare with the bare `ensure(pred, R)` form, which suits flag-style rejections (`NotOwner`, `MismatchedIds`).

### Transform / inspect within the pipeline

These operate on a `Sealed[F, A, R]` once you're already inside it. Most are flavors of the cats `Monad` / `Functor` API specialized for the result-ADT shape.

| Combinator                                  | Behavior                                                                                                  |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `.map(f)` / `.flatMap(f)` / `.void`         | Standard monadic plumbing on the value channel.                                                            |
| `.semiflatMap(f: A => F[B])`                | Like `flatMap` but for an effectful step that doesn't itself short-circuit.                                |
| `.complete(f: A => R)` / `.completeWith(f: A => F[R])` | Terminate the pipeline with a result computed from the current value. Drops everything after.    |
| `.tap(f)` / `.flatTap(f)`                   | Side-effect on the value, keep the value. The latter is effectful.                                         |
| `.flatTapWhen(cond, f)` / `.flatTapWhenNot(cond, f)` | Conditional side-effects.                                                                          |
| `.leftSemiflatTap(f: R => F[_])`            | Side-effect on the *short-circuit* branch, after the pipeline has already decided to halt. Logging on rejection. |
| `.leftSemiflatMap(f: R => F[R'])`           | Transform the short-circuit branch with an effect. Rare; useful for adapting between result ADTs.          |
| `.biSemiflatMap` / `.biSemiflatTap`         | Both branches at once. Rarely needed; reach for them when a step has to handle success and rejection symmetrically. |
| `.either`                                   | Surface the current `Either[R, A]` as the value channel — the pipeline becomes "always continues, value is the fold." |
| `.inspect(pf)` / `.inspectF(pf)`            | Pattern-match peek over `Either[R, A]`. Quick observability hooks.                                         |
| `.foldM(left, right)`                       | Full bifold to a new `Sealed`. Heaviest combinator; almost never needed in service code.                    |

`leftSemiflatTap` is the one most underused: it's the canonical way to log on short-circuit without changing the result.

```scala
result <- step
            .valueOr[R](R.NotFound)
            .leftSemiflatTap(r => logger.warn(s"NotFound short-circuit: $r"))
```

### Collapse the pipeline

| Combinator | Behavior                                                                                  |
| ---------- | ----------------------------------------------------------------------------------------- |
| `.run`     | Collapse `Sealed[IO, A, R]` to `IO[R]`. Required: `A <:< R` (the success case is a result). |

`.run` is the only way out. Don't try to operate on a `Sealed` after it; convert back to `IO` and stay there.

## When to reach for it

Use it when a service method:

- Returns a result ADT with three or more cases (one success, two-plus rejections).
- Has more than one step that can short-circuit.
- Mixes IO effects, Option results, and Either results that all need to fold into the same response.

That covers most write-side service methods in this codebase: `createAuction`, `placeBid`, `cancelAuction`, `attachImage`, `commitUpload`, `serveVariant`. They all share the same shape.

Skip it when:

- The method has one path. Plain `flatMap`/`for` reads better; sealed-monad adds no value.
- The method has one short-circuit. An `OptionT` or a single `flatMap { case None => …; case Some(_) => … }` is fine.
- The method's "failures" are infrastructure exceptions, not domain results. Those belong in `IO`'s error channel; sealed-monad isn't an error monad.

## How it differs from `EitherT`

`EitherT[IO, Error, A]` works fine when there's a clear success/error split. The asymmetry it bakes in — `Right` is "real," `Left` is "exceptional" — doesn't fit a result ADT where every case is a legitimate domain answer. `BidTooLow(currentHighest)` isn't an error; it's information the API has to return.

Sealed-monad puts every case on equal footing. Short-circuiting just means "we already have the answer; don't do more work." That's also why the type parameter is called `R` (result), not `E` (error). The vocabulary matters — once you start thinking of `BidTooLow` as an "error," you'll feel pressure to log it, alert on it, treat it differently from success. It's not. It's a reply.

`MonadError`'s exception channel has the same problem and worse — it pulls domain decisions into a runtime mechanism designed for exceptional control flow.

## How it differs from a manual `flatMap` chain

You *can* write all this with `flatMap`:

```scala
auctionRepository.findForUpdate(command.auctionId).flatMap {
  case None          => IO.pure(PlaceBidResult.AuctionNotFound)
  case Some(auction) =>
    bidRepository.highestBid(command.auctionId).flatMap { previousHighest =>
      Clock[IO].realTimeInstant.flatMap { now =>
        IdGenerator.generateId(BidId).flatMap { bidId =>
          auction.placeBid(...).fold(
            rej => IO.pure(rejectionToResult(rej)),
            bid => bidRepository.save(bid).flatMap(saved =>
              notifyOutbid(...).as(PlaceBidResult.BidPlaced(saved, auction))
            )
          )
        }
      }
    }
}
```

That works. It also drifts right by one indent level per step, makes the early-return cases fight for visual weight against the happy path, and accumulates a quoting mess if you want to extract pieces.

The sealed-monad version reads top-to-bottom, every short-circuit reads as one line, and the happy path stays flat.

## Keep it local to one method

`Sealed[IO, A, R]` is an implementation detail, not an API. Public method signatures return `IO[Result]`; the `(for { … }).run` shape lives inside the method body and never escapes it.

If you see `Sealed` in a return type, something's probably off:

- Callers have to learn the sealed-monad API just to compose the result. The library bleeds out of the implementation.
- The contract widens from "here's the answer" to "here's a pipeline you can splice into yours." That's a heavier commitment than most methods need to make.
- The chosen result ADT leaks. If a helper returns `Sealed[IO, A, OneResult]`, every caller's result ADT has to subsume `OneResult` or the types fight.

The fix is almost always: `.run` inside the helper and return `IO[Result]`. The caller `.seal`s it back in, scoped to whatever ADT *they* care about. Each method owns its own short-circuit semantics; the discipline scales because every method's signature still reads "give me an answer in `IO`."

The exception is helpers private to one service file that share a single result ADT and exist only to factor a long pipeline. Even then, prefer `IO[Result]` first; reach for an exposed `Sealed` only when the alternative is genuinely worse.

## The result-ADT contract

Sealed-monad is the *combinator* layer. The *type* it composes over is the result ADT — a sealed enum where one case is success (often carrying a payload), and the rest are domain rejections (often carrying context):

```scala
enum PlaceBidResult {
  case BidPlaced(bid: Bid, auction: Auction)
  case AuctionNotFound
  case AuctionNotOpen
  case AuctionNotStarted
  case AuctionEnded
  case CannotBidOnOwnAuction
  case AlreadyHighestBidder
  case BidTooLow(currentHighest: Price)
}
```

The router pattern-matches the result and produces an HTTP response per case. The compiler enforces exhaustiveness; adding a new rejection case means the router stops compiling until you handle it.

[error-handling.md](error-handling.md) covers the result-ADT discipline at the boundary; [domain-modeling.md](domain-modeling.md) covers the domain-side rejection enums (`BidRejection`, `CancellationRejection`) that result enums often map onto.

## Reading rules

- **`(for { … } yield Success).run` is the shape**. If you don't see those bookends, sealed-monad isn't being used here.
- **Every line is one of**: `_ <- step.seal` (no short-circuit), `x <- step.valueOr(R)` (Option short-circuit), `x <- step.valueOrF(io)` (Option short-circuit with logging), `x <- step.ensure(p, R)` (predicate short-circuit), `x <- e.rethrow[IO]` (Either short-circuit). Anything else is a sign someone reached for an idiom the rest of the codebase doesn't use.
- **The yielded value is the success case** of the result ADT. The short-circuit cases are scattered through the comprehension's combinators.
- **`.run` is a one-shot escape**. Don't chain operations on a `Sealed` after `run` — convert back to `IO` and stay there.

## Limits worth knowing

- **It's not a transaction wrapper.** `transactor.inTransaction { ... }` still does that work; sealed-monad runs *inside* the transaction.
- **It composes with cats-effect, not over it.** Concurrency, resource handling, retries — keep using `cats.effect.IO`. Sealed-monad doesn't replace any of that.

## Where to look next

- [error-handling.md](error-handling.md) — the broader pattern: result ADTs, no-throw services, exhaustive router matches.
- [domain-modeling.md](domain-modeling.md) — `BidRejection` and friends; what each result ADT case maps onto.
- [http.md](http.md) — how the router renders each result case as an HTTP response.
