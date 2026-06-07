# Cache

In-process caches, behind a swappable runtime. The trait is small on purpose: production uses Caffeine via Scaffeine; tests use a deterministic `ConcurrentHashMap`. The application code only sees the trait.

## The shape

```scala
trait Cache[K, V] {
  def get(key: K): IO[Option[V]]
  def put(key: K, value: V): IO[Unit]
  def invalidate(key: K): IO[Unit]
  def invalidateAll: IO[Unit]
  def size: IO[Long]
}

trait CacheRuntime {
  def expiring[K, V](expireAfterWrite: FiniteDuration, maxSize: Long): Cache[K, V]
}
```

`CacheRuntime` is the factory; it knows how to build caches. Modules ask for one in their `wire`-time setup and get back a `Cache[K, V]` they own for the lifetime of the application. Two policies are baked in for now: time-to-live (`expireAfterWrite`) and an upper bound on entry count (`maxSize`). Add new factory methods to `CacheRuntime` if you want different policies (`expireAfterAccess`, `refreshAfterWrite`, weight-based eviction) — keep the public surface narrow.

## Building a cache

A typical usage, distilled from `VivinoGatewayLive` (the real method also wraps the call in `.timeout(...)` and folds errors via `.handleErrorWith(...)` — see [external-apis.md](external-apis.md)):

```scala
class VivinoGatewayLive(
  http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  cacheRuntime: CacheRuntime,
  circuitBreaker: IO[CircuitBreaker[IO]]
)(using TelemetryContext) extends VivinoGateway with LoggingSupport {

  private val cache = cacheRuntime.expiring[(WineName, Option[Vintage]), Option[VivinoRating]](
    expireAfterWrite = 24.hours,
    maxSize          = 10_000
  )

  override def findRating(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]] =
    cache.get((wineName, vintage)).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        fetch(wineName, vintage)
          .flatTap(result => cache.put((wineName, vintage), result))
    }
}
```

Three things to notice:

- **One cache per usage site.** Each `cacheRuntime.expiring(...)` call returns a fresh, independent cache. There's no shared global namespace; the gateway owns its cache, the next service owns its own.
- **The key carries everything that distinguishes lookups.** Tuples are convenient when the lookup has multiple parameters. Whatever you key on, make sure equal keys really mean "the same thing" — fuzzy keys cause stale-value bugs.
- **The value can be `Option[V]`.** `findRating` caches `None` ("Vivino has no match for this wine") with the same TTL as a hit, so a cold lookup that misses doesn't get repeated for 24 hours. If you want to retry misses sooner, cache only the `Some` case.

## Cache-aside

The pattern in `VivinoGateway` is the standard cache-aside flow: check the cache, fall through to the underlying call on miss, store the result. Make the fall-through path idempotent — if two requests miss simultaneously, both will fetch and both will write; that's fine for read-only lookups but problematic for anything with side effects.

For values where you want to avoid duplicate fetches, layer in a single-flight wrapper (`Resource`/`Mutex` keyed on the cache key). The runtime doesn't ship one — add it when you need it, scoped to the call site.

## What to cache

Good fits:

- Idempotent reads from external services (Vivino lookups, geocoding, currency rates).
- Computed values that are expensive to derive but stable for the relevant time window (rendered templates, parsed configurations).
- Negative results that are themselves expensive to recompute.

Bad fits:

- Database rows that other instances might mutate. The cache won't see the update; users see stale state until the TTL expires. If you really need this, use the [event bus](event-bus.md) to broadcast invalidations, or swap to a shared cache (Redis).
- Per-request data. The cache survives the request; what you wanted was a `Local[IO, …]` or just a `lazy val` in scope.
- Anything where freshness matters more than latency. A 5-minute-stale price might lose a user a sale. Don't cache prices.

If you find yourself reaching for a cache to "make this fast," pause to ask whether the underlying thing is too slow because the cache is missing or because the data model is wrong. Caches paper over a lot of issues that should be fixed elsewhere.

## TTL and maxSize

`expireAfterWrite` is a soft real-time bound on staleness. After that interval, the entry is gone. Caffeine's expiry is lazy — the entry is removed on the next access that touches it; size doesn't go down the instant the TTL elapses. For metrics that count cache size, treat `size` as approximate.

`maxSize` is an upper bound on entry count. When the cache is full, Caffeine's W-TinyLFU policy evicts the least-recently-used-and-rarely-hit entries. Pick a number that's a few multiples of your steady-state working set; too small and you churn, too large and you waste memory.

A guideline:
- Per-key memory cost × `maxSize` should be a small fraction of your heap. A 10k-entry cache of 1KB tuples is 10MB — fine. A 10k-entry cache of holds-onto-a-DB-row-graph values can balloon unexpectedly.
- TTL × your request rate gives a rough upper bound on cache-miss rate. If you're seeing a 90% miss rate, your TTL is too short or your `maxSize` is too small.

## Production runtime

`Main` wires the production runtime once:

```scala
cacheRuntime = CacheRuntime.scaffeine
```

Scaffeine is a thin Scala wrapper over Caffeine. Caffeine is the JVM gold-standard local cache — high-performance, well-tested, used everywhere. The wrapper exposes only what the project's trait needs; you never touch Caffeine directly from app code.

This runtime is **per-JVM**. Each instance has its own cache; entries don't replicate across instances. For a single-process app this is exactly what you want. For multi-instance deployments, see "Multi-instance considerations" below.

## Test runtime

`TestCacheRuntime.unbounded` is what `TestApplicationLoader` wires:

```scala
override protected lazy val cacheRuntime: CacheRuntime = TestCacheRuntime.unbounded
```

Backed by a `ConcurrentHashMap`, it ignores both `expireAfterWrite` and `maxSize`. Two consequences:

- **No eviction.** Anything you put stays until you `invalidate` it or the test ends. Good for deterministic assertions ("after this call, the cache contains X").
- **No TTL.** Tests don't have to sleep waiting for entries to expire. If a test specifically wants to verify TTL behavior, it should use `CacheRuntime.scaffeine` directly — `CacheSpec.scala` does both.

The `CacheSpec` runs the same shared assertions against both runtimes, so behaviour stays in sync. The runtime-specific assertions (TTL eviction for Scaffeine, no-TTL behaviour for the test runtime) are isolated.

## Multi-instance considerations

If you run more than one process, the `scaffeine` runtime gives each process an independent cache. That's fine for read-through caches over external APIs (each instance pays for its own cold start, but values stay correct). It's a problem when:

- Cache invalidation has to fan out across instances. Example: an admin updates a feature flag; one process picks it up immediately, the other serves the stale flag for the rest of the TTL.
- The cache holds derived state from the database. Instances see different snapshots until TTLs align.

Two ways out:

- **Broadcast invalidations through the event bus.** Subscribe each module's caches to invalidation events; on receipt, call `cache.invalidate(key)`. The Postgres `LISTEN/NOTIFY` backend already fans events to every instance.
- **Swap the runtime to a shared cache.** Add `CacheRuntime.redis(...)` (or whatever backing store you pick), construct it in `Main`, and the entire codebase keeps using `cacheRuntime.expiring(...)` unchanged. The trait is the swap point.

The template doesn't ship a Redis runtime — when you need it, add it.

## Lifecycle

Caches live as long as the JVM. They're constructed once (as `lazy val`s in modules / classes), captured in closures, and stay until the process exits. Don't try to scope them per-request or per-session; if you do, the cache hit rate will be ~0 and you've added overhead for nothing.

Conversely, don't keep references to caches outside their owning class. The gateway owns the gateway's cache; nothing else should hold a reference.

## Observability

The cache trait has no built-in metrics or tracing. If you want hit/miss counters, add them at the call site:

```scala
private val cacheHits   <- tc.meter.counter[Long]("vivino.cache.hits").create
private val cacheMisses <- tc.meter.counter[Long]("vivino.cache.misses").create

cache.get(key).flatMap {
  case Some(v) => cacheHits.inc().as(v)
  case None    => cacheMisses.inc() *> fetch(key)
}
```

Caffeine has its own statistics API (`Scaffeine().recordStats()`) — not currently wired through the trait. Easy to add if you want hit-rate metrics across the board.

## Where to look next

- [external-apis.md](external-apis.md) — the Vivino gateway as the canonical cache user.
- [event-bus.md](event-bus.md) — for cross-instance cache invalidation.
- [observability.md](observability.md) — adding counters/histograms around cache operations.
