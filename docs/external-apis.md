# External APIs

External-API calls go through sttp's `WebSocketStreamBackend[IO, Fs2Streams[IO]]`, layered with logging, metrics, and tracing in `ApplicationLoader`. A "gateway" is a small class that wraps one external service behind a domain-shaped interface — caching, timeouts, error mapping, retries, and breaker protection live in the gateway, not at call sites.

`VivinoGateway` is the worked example: looks up wine ratings from the Vivino-fronted Algolia index. Same shape applies to any third-party HTTP API.

## The HTTP client

`Main` builds one shared HTTP client and threads it into every module that needs to make outbound calls. The construction in `ApplicationLoader`:

```scala
lazy val httpClient: WebSocketStreamBackend[IO, Fs2Streams[IO]] =
  OpenTelemetryTracingBackend(
    OpenTelemetryMetricsBackend(
      LoggingBackend(httpBackend, httpClientLogger, LogConfig.Default.copy(
        logRequestBody = true, logResponseBody = true
      )),
      telemetryContext.underlying
    )
  )
```

What each layer does:

| Layer                          | Effect                                                                    |
| ------------------------------ | ------------------------------------------------------------------------- |
| `HttpClientFs2Backend` (base)  | Java 11 `HttpClient` wrapped in fs2 streams. The actual transport.        |
| `LoggingBackend`               | Per-request log line through the project's logger, at the level controlled by `logging.loglevel-request-response`. Bodies are always logged — drop the level in production if you don't want full request/response payloads in logs. |
| `OpenTelemetryMetricsBackend`  | Records request count, duration, status-code histograms.                  |
| `OpenTelemetryTracingBackend`  | Each outbound call is a child span of the inbound request's trace.        |

The result is a single `WebSocketStreamBackend` instance shared across all gateways. Don't construct your own — accept the abstract `httpClient` from your module's dependencies and pass it to gateways.

## Anatomy of a gateway

A gateway is one trait (the public, swappable surface) and one implementation class (the production wiring):

```scala
trait VivinoGateway {
  def findRating(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]]
}

class VivinoGatewayLive(
  http: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  cacheRuntime: CacheRuntime,
  circuitBreaker: CircuitBreaker[IO]
)(using TelemetryContext)
    extends VivinoGateway with LoggingSupport {
  import VivinoGateway.*

  private val endpoint       = uri"https://9takgwjuxl-dsn.algolia.net/1/indexes/WINES_prod/query"
  private val applicationId  = "9TAKGWJUXL"
  private val apiKey         = "60c11b2f1068885161d95ca068d3a6ae"
  private val requestTimeout = 3.seconds

  private val cache = cacheRuntime.expiring[(WineName, Option[Vintage]), Option[VivinoRating]](
    expireAfterWrite = 24.hours, maxSize = 10_000
  )

  override def findRating(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]] =
    cache.get((wineName, vintage)).flatMap {
      case Some(cached) => IO.pure(cached)
      case None =>
        fetch(wineName, vintage)
          .timeout(requestTimeout)
          .flatTap(result => cache.put((wineName, vintage), result))
          .handleErrorWith(t => logger.warn(t)(s"Vivino lookup failed for $wineName").as(None))
    }

  private def fetch(wineName: WineName, vintage: Option[Vintage]): IO[Option[VivinoRating]] = {
    val queryText = vintage.map(v => s"${wineName.unwrap} ${v.unwrap}").getOrElse(wineName.unwrap)
    val payload   = AlgoliaQuery(query = queryText, hitsPerPage = 6).asJson.noSpaces
    val request = basicRequest
      .post(endpoint)
      .header("x-algolia-api-key", apiKey)
      .header("x-algolia-application-id", applicationId)
      .contentType(MediaType.ApplicationJson)
      .body(payload)
      .response(asJson[AlgoliaResponse])

    request.send(http).flatMap { response =>
      response.body match {
        case Right(parsed) => IO.pure(pickBestMatch(wineName, vintage, parsed.hits))
        case Left(error)   => IO.raiseError(error)
      }
    }
  }
}

object VivinoGateway {
  // pure helpers and on-the-wire shapes — package-private so the spec can use them
  private[gateways] def pickBestMatch(...): Option[VivinoRating] = …

  private[gateways] final case class AlgoliaQuery(query: String, hitsPerPage: Int) derives Encoder.AsObject
  private[gateways] given Configuration = Configuration.default.withSnakeCaseMemberNames
  private[gateways] final case class AlgoliaResponse(hits: List[AlgoliaHit]) derives ConfiguredCodec
  // …
}
```

Five things to notice:

- **The trait is domain-shaped.** `findRating` takes `WineName`, returns `Option[VivinoRating]`. Callers see no Algolia, no JSON, no HTTP. The Vivino-vs-Algolia mismatch (it's actually a different API in front) lives entirely inside the implementation.
- **The class takes the http client and any runtimes it needs.** Wired with macwire's `wire[VivinoGatewayLive]` — same way services and routers are wired.
- **Caching, timeout, and error swallowing live in the class.** The route doesn't know whether the answer was cached, timed out, or came from a fresh call. It just gets `Option[VivinoRating]` back, with `None` covering "not found" and "lookup failed."
- **On-the-wire types are package-private in the companion.** `AlgoliaQuery`, `AlgoliaResponse`, `AlgoliaHit` never escape the package. The class imports them with `import VivinoGateway.*`. The domain (`VivinoRating`) is what the rest of the application sees.
- **`ConfiguredCodec` for snake_case responses, `Encoder.AsObject` for camelCase requests.** Real-world APIs are usually inconsistent. Pick the codec configuration per direction.

## Trait + `Live` class

Why two types instead of one?

- **The trait is the swap point.** `VivinoGateway` is one method, so the test stub is a one-liner: `val vivinoGateway: VivinoGateway = (_, _) => IO.pure(None)`. No subclass to name, no fields to satisfy. Drop the trait and you'd need a fake class with a fake cache, or a mock framework, or a real http client wired to a fake backend — all heavier than implementing one method.
- **The class is the production wiring.** `Live` makes "this is what the app actually runs" obvious; alternatives like circuit-breaker-wrapped, instrumented, or recorded variants would be siblings. macwire injects it the same way it injects any other class.
- **Static helpers stay in the companion.** Pure functions like `pickBestMatch` and the Algolia case classes live in `object VivinoGateway` so the spec can call them without instantiating anything. The class imports them with `import VivinoGateway.*`.

This matches the rest of the codebase: traits exist where production needs a swap point — `ExternalAuthVerifier` + `FirebaseService`, `Transactor` + `PgTransactor`, `ObjectStore` + `S3ObjectStore`. Plain classes (services, routers, repositories) skip the trait when nothing's substituting them.

## Caching

`CacheRuntime.expiring[K, V](expireAfterWrite, maxSize)` returns an in-memory cache (Scaffeine / Caffeine under the hood). Wrap the gateway call with cache-then-fetch. For idempotent reads — like rating lookups — this is usually all you need. See [cache.md](cache.md) for cache mechanics, including how to swap to a Redis-backed runtime.

Choose a TTL that matches the freshness contract: 24h is fine for "wine ratings rarely change daily"; for things you need to reflect minute-by-minute, use a shorter TTL or invalidate explicitly.

## Timeouts

Every outbound call should have a timeout. The pattern in `VivinoGateway`:

```scala
fetch(wineName, vintage).timeout(requestTimeout)
  .handleErrorWith(t => logger.warn(t)(...).as(None))
```

`IO.timeout` raises a `TimeoutException` if the underlying call doesn't complete in time. The `handleErrorWith` catches both timeout and underlying transport errors and folds them into the domain answer (`None`).

Don't rely on the underlying HTTP client's timeout alone — pick a per-call timeout that's much shorter than your inbound request's overall budget. A user-facing route shouldn't wait 30 seconds for a third-party that's degraded; pick 2–3 seconds and treat the absence of the answer as the answer.

## Errors

Three failure modes a gateway has to handle:

1. **Transport failure.** Connection refused, DNS lookup failed, TCP reset. sttp surfaces as a thrown exception.
2. **HTTP error response.** 4xx, 5xx. With `.response(asJson[T])`, a non-2xx body becomes `Left(Throwable)`. With `.response(asString)` you get the raw body and have to inspect status yourself.
3. **Decoding failure.** 200 OK with a body that doesn't match the schema. Same `Left(Throwable)` path as HTTP errors when using `asJson[T]`.

The gateway decides which of these are recoverable. `VivinoGateway` treats all three as "no rating" — it's a non-essential lookup. A gateway calling a payment provider should treat them very differently: log, alert, raise a typed error to the caller.

Don't catch `Throwable` at the call site. Either the gateway converts to a domain answer (`None`, `Either[ErrorADT, Result]`), or it raises a typed exception the caller must handle.

## Retries and circuit breakers

Outbound calls fail in two distinct ways:

- **Transiently** — a dropped connection, a 30-second blip, a slow first call after a dep's cold-start. Retrying usually works.
- **Sustained** — the dep is genuinely down for minutes. Retries make it worse: every failing request still consumes its full retry burst against the sick dep.

`VivinoGateway` layers two libraries to cover both:

- **[cats-retry](https://cb372.github.io/cats-retry/)** retries transient failures with exponential backoff.
- **[davenverse/circuit](https://davenverse.github.io/circuit)** opens after sustained failures so subsequent calls fast-fail without touching the dep.

The composition — circuit wraps retry — so each breaker outcome reflects the full retry burst, not individual attempts:

```scala
val attempt = fetch(wineName, vintage).timeout(requestTimeout)
circuitBreaker
  .protect(
    retryingOnErrors(attempt)(
      policy = retryPolicy,
      errorHandler = (e, details) =>
        if (isTransient(e))
          logger.warn(e)(s"transient (retry ${details.retriesSoFar + 1})").as(HandlerDecision.Continue)
        else
          IO.pure(HandlerDecision.Stop)
    )
  )
  .flatTap(result => cache.put((wineName, vintage), result))
  .handleErrorWith(t => logger.warn(t)("failed").as(None))
```

Five things to notice:

- **Per-attempt timeout.** `attempt = fetch.timeout(requestTimeout)` — each retry attempt gets its own 3-second budget. If timeout sat outside the retry, retries would race a single shared deadline.
- **Retry inside, breaker outside.** `cb.protect(retryingOnErrors(...))` — retry exhausts first; the breaker only sees the *final* outcome of the burst. A "down" dep produces N retries per request before incrementing the breaker. The breaker opens after sustained failure *across* multiple full requests.
- **Error classifier decides what to retry.** `isTransient` returns true for `TimeoutException`, `SttpClientException` (sttp's transport-error wrapper), and `IOException`. Returns false for parse errors and 4xx/5xx propagated from `.response(asJson[T])` — those won't be fixed by retrying. `CircuitBreaker.RejectedExecution` never reaches the classifier: when the breaker is open, control never enters the retry block.
- **`handleErrorWith` outermost.** Once retries are exhausted *or* the breaker is open, the error propagates out — `handleErrorWith` folds it into the domain answer (`None`). This is the gateway's fail-soft contract; a payment gateway would raise a typed error here instead.
- **Successful results are cached, failures aren't.** `.flatTap(cache.put)` runs only on the success path. Errors don't poison the cache.

### Retry policy

```scala
val retryPolicy: RetryPolicy[IO, Any] =
  limitRetries[IO](2).join(exponentialBackoff[IO](200.millis))
```

- `limitRetries(2)` caps the burst at 2 retries (3 attempts total).
- `exponentialBackoff(200.millis)` waits 200ms, then 400ms, then 800ms… — predictable backoff that doesn't synchronize retry storms across callers.
- `.join` combines them: stop at whichever limit fires first.

See [cats-retry's policy combinators](https://cb372.github.io/cats-retry/docs/policies.html) for `constantDelay`, `fibonacciBackoff`, `fullJitter`, and friends.

### Circuit breaker

```scala
val circuitBreaker: Resource[IO, CircuitBreaker[IO]] =
  Resource.eval(
    CircuitBreaker.of[IO](
      maxFailures = 5,
      resetTimeout = 30.seconds,
      backoff = Backoff.exponential,
      maxResetTimeout = 5.minutes
    )
  )
```

- `maxFailures = 5` — after 5 consecutive retry-bursts fail, the breaker opens.
- `resetTimeout = 30.seconds` — initial wait before allowing a single probe call.
- `backoff = Backoff.exponential` + `maxResetTimeout = 5.minutes` — if the probe also fails, the next wait doubles, then doubles again, capped at 5 minutes. Without backoff, the breaker would probe a long-down dep every 30 seconds forever.

When the breaker is open, `cb.protect(...)` raises `CircuitBreaker.RejectedExecution` immediately without invoking the protected action.

### Wiring

The breaker holds mutable state (a `Ref`), so it's an `IO`-allocated resource:

- The gateway's companion exposes construction as `Resource[IO, CircuitBreaker[IO]]` — tuning lives next to the gateway it protects, not in HOCON.
- `Main` acquires it once at startup: `vivinoCircuitBreaker <- VivinoGateway.circuitBreaker`.
- `ApplicationLoader` threads it through to `AuctionModule` as an abstract `val`.
- macwire injects it into `VivinoGatewayLive` by type.

**One breaker per gateway.** Different deps have different failure characteristics: a payment provider wants a low threshold and a long cool-down; a non-essential rating lookup wants higher tolerance. Don't share a breaker across unrelated services. (If you wire two gateways that each need a `CircuitBreaker[IO]`, macwire will fail to disambiguate — give each a wrapper type or pass by name.)

### When NOT to add this

For the worked example — a non-essential, fail-soft rating lookup — the breaker is arguably overkill: errors already fold to `None` and the cache absorbs most of the load. It's wired anyway as a copy-pasteable template. Skip retry+CB when:

- The call is advisory and the cache absorbs most failures.
- Volume is low enough that retry storms can't pressure the dep.
- The dep is solid enough that you'd rather see real failures than mask them.

Add retry+CB when:

- The call is on a user-facing critical path (auth verification, payment, primary content).
- The dep has documented downtime windows or you've observed sustained outages.
- Traffic is high enough that a degraded dep would receive thousands of doomed retries.

## Configuration

The Vivino gateway hardcodes the Algolia endpoint and credentials. That's deliberate — they're the public Vivino front-end's keys, not secrets. For a real third-party with secrets, load them from config:

```hocon
some-api {
  endpoint = ${SOME_API_ENDPOINT}
  api-key  = ${SOME_API_KEY}
  timeout  = 3s
}
```

Add a `final case class SomeApiConfig(...)` derived from `ConfigReader`, load it in `Main` or your module, and pass it to the gateway factory. Keep secrets out of code; load them from environment variables only.

## Adding a new gateway

1. **Define the trait in `<module>/gateways/`.** Domain-shaped methods, no HTTP types in the signature.
2. **Build the `MyGatewayLive` class** taking the shared `httpClient` and any runtimes (cache, scheduler, etc.) as constructor parameters.
3. **Keep on-the-wire types `private[gateways]` in the companion** — codec annotations on the case classes inside `object MyGateway`. Pure helpers (parsers, matchers) go there too.
4. **Wrap with cache, timeout, and error handling** as appropriate. Decide if errors fold into the domain (return `None` / a result ADT) or surface (raise a typed exception).
5. **Wire it in your module** as `protected lazy val gateway: MyGateway = wire[MyGatewayLive]`.
6. **Override in tests** with a stub. The trait is one method; the test stub is one line.

## Testing

Unit tests for gateway logic that doesn't depend on HTTP — like `VivinoGateway.pickBestMatch` — are pure-function tests; see `VivinoGatewaySpec`.

For HTTP-path tests, sttp ships `WebSocketStreamBackendStub` you can wire instead of `HttpClientFs2Backend`. `VivinoGatewayResilienceSpec` uses it with a `Ref`-driven response to exercise the retry-then-breaker layering end-to-end: counting backend calls to verify retries fire, then that the breaker opens and stops them. The dep is `"com.softwaremill.sttp.client4" %% "cats" % sttpV % "test"`, which brings sttp's CE3 `MonadError` instance.

For broader integration tests, the auction tests stub the gateway at the trait level (`val vivinoGateway: VivinoGateway = (_, _) => IO.pure(None)`) so service tests don't depend on Algolia being up — the resilient wiring is exercised only in the gateway's own spec.

## Where to look next

- [cache.md](cache.md) — `CacheRuntime` mechanics; production swap-out.
- [observability.md](observability.md) — every outbound call is a child span; see metrics in OpenObserve.
- [error-handling.md](error-handling.md) — Result-ADT-per-method conventions.
