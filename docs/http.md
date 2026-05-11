# HTTP

The HTTP stack is http4s for the server and JSON entity codecs, [stir](https://github.com/theiterators/stir) for the routing DSL, and [baklava](https://github.com/theiterators/baklava) for OpenAPI generation. Routers extend `BaseRouter`, which mixes in everything you need to build a route.

## The stack

```
http4s          server (Ember), HTTP types, JSON entity codecs (via circe).
stir            akka-http-style routing DSL on top of http4s — directives,
                path matchers, marshalling.
baklava         OpenAPI / TypeScript-rest spec generation. Hooks into stir at
                test time and emits `target/baklava/openapi/openapi.yml`
                plus a Swagger UI served from /swagger in dev.
kebs            opaque-type / enum unmarshallers and matchers for stir, so
                `JavaUUID.as[AuctionId]` and `parameter("status".as[AuctionStatus])`
                just work.
```

You write routes the same way regardless of which library is doing what at any particular moment — stir hides http4s, baklava observes stir, kebs converts your domain types into stir matchers.

## Anatomy of a router

A router is a class extending `BaseRouter` (and any directive traits it needs, like `RateLimitDirectives`). Its constructor takes the services it calls and any cross-cutting context (event buses, rate limiter runtime, the current `TelemetryContext`).

```scala
class AuctionRouter(
  auctionService: AuctionService,
  eventBus: EventBus[AuctionEvent],
  rateLimiterRuntime: RateLimiterRuntime
)(using TelemetryContext) extends BaseRouter with RateLimitDirectives {

  val routes: Route = {
    (get & path("auctions") & pathEndOrSingleSlash) {
      complete {
        auctionService.listAuctions(...).map[ToResponseMarshallable] { views =>
          Ok -> views.map(AuctionDto(_))
        }
      }
    } ~
    (get & path("auctions" / JavaUUID.as[AuctionId]) & pathEndOrSingleSlash) {
      auctionId =>
        complete { auctionService.getAuction(auctionId).map { … } }
    }
  }

  def authedRoutes(authContext: AuthContext): Route = { … }
}
```

The two methods are conventional, not enforced: `routes` for public routes (matched by `RouteProvider`), `authedRoutes(auth)` for routes that need an `AuthContext` (matched by `AuthRouteProvider`). A router can also expose `wsRoutes(wsb)` for WebSocket routes.

## What `BaseRouter` gives you

- **`Directives` and `WebSocketDirectives` from stir.** `path`, `get`, `post`, `entity(as[T])`, `parameters(...)`, `complete`, `~` (route concatenation), `&` (directive concatenation), `pathPrefix`, `pathEndOrSingleSlash`, etc. The vocabulary is akka-http's; if you've used that, you know stir.
- **`JsonProtocol`.** circe's `Encoder` / `Decoder` are in scope under those names, plus kebs derivations for opaque types and enums. `entity(as[CreateAuctionRequest])` decodes the body; returning an `(Status, Foo)` pair encodes the response. See [json.md](json.md) for the codec-derivation story.
- **kebs unmarshallers and matchers.** Path segments and query parameters can be typed: `JavaUUID.as[AuctionId]`, `Segment.as[VariantSpec]`, `parameter("status".as[AuctionStatus].?)`. The conversion is failed-as-rejection, so a malformed UUID returns 400 automatically.
- **`Status.*` exported.** `Ok`, `Created`, `NotFound`, `Forbidden`, etc. are accessible directly.
- **An `error(...)` helper** for problem-detail-shaped error responses (see below).

## Errors

`BaseRouter.error(status, typeTag, title, detail = None)` returns `IO[ToResponseMarshallable]` with a JSON body shaped like [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) problem-details, augmented with the current trace ID:

```json
{
  "type": "result:auction-not-found",
  "status": 404,
  "title": "Auction not found",
  "instance": "trace-id:7a1f8e6c…"
}
```

Service results are typically ADTs whose `Committed` / `NotFound` / `NotOwner` cases the route maps to status codes:

```scala
auctionImageService.commitUpload(...).map[ToResponseMarshallable] {
  case CommitUploadResult.Committed(image) => Created -> AuctionImageDto(image, apiPrefix)
  case CommitUploadResult.AuctionNotFound  => error(NotFound,  "auction-not-found", "Auction not found")
  case CommitUploadResult.NotOwner         => error(Forbidden, "not-owner",         "Only the seller can commit uploads")
  case CommitUploadResult.ObjectNotFound   => error(NotFound,  "object-not-found",  "Direct upload not found at the expected key")
  case CommitUploadResult.Conflict         => error(Conflict,  "image-id-conflict", "This image id is already in use")
}
```

Don't throw to signal a business outcome. Reserve exceptions for genuinely exceptional conditions (DB connection lost, JVM OOM, bug). See [error-handling.md](error-handling.md).

## Route assembly

`ApplicationLoader.routes(wsb)` assembles all module-contributed routes in one place:

```scala
def routes(wsb): Route =
  rawPathPrefix(Slash ~ apiVersion) {                          // /v1
    authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
      route(auth) ~ route ~ wsRoutes(auth, wsb) ~ wsRoutes(wsb)  // authed + public, both HTTP and WS
    } ~
    // public-only fallback if auth rejects
    (route ~ wsRoutes(wsb))
  } ~
  adminRoutes ~                                                // /admin/*  (basic-auth gated)
  (if (env == "dev") mailPreviewRouter.routes ~ baklavaDocs)    // dev-only
```

Logging and tracing are layered on by middleware in `Main`, so individual routers don't have to call them.

## CORS

http4s' `CORS` middleware wraps the whole `HttpApp` in `Main` (so preflight `OPTIONS` is answered before routing). The `cors` config block:

```hocon
cors {
  enabled = true                     // CORS_ENABLED=false drops the middleware entirely
  allowed-origins = ""               // comma-separated origins; "*" = any; empty = derive (below) — CORS_ALLOWED_ORIGINS
  max-age = 1h                       // how long browsers may cache the preflight — CORS_MAX_AGE
}
```

`allowed-origins` resolution (in `Cors.policy`):

- an explicit list (`https://app.example.com,https://www.example.com`) → exactly those origins
- `*` → any origin
- empty (the default) → **any origin in dev** (`app.environment = "dev"`), otherwise **the host of `http.base-url`** — a safe fallback that's zero-config when the SPA and the API share an origin behind one proxy, and never silently `*` in production

Methods and request headers are allowed wildcard — what a typical SPA wants; lock them down in `Cors.policy` if you need to.

**No credentials.** `Access-Control-Allow-Credentials` is not set: auth here is a client-set `Authorization: Bearer <jwt>`, which doesn't need it. If you add cookie-based auth, turn credentials on in `Cors.policy` **and** switch `allowed-origins` to a specific list — a `*` origin with credentials is rejected by browsers.

**Production:** your frontend is usually a different origin from the API, so set `CORS_ALLOWED_ORIGINS` to your real frontend origin(s). The `base-url` fallback only covers the same-origin case.

## OpenAPI and Swagger UI

baklava generates the OpenAPI spec by observing stir routes during the test suite. Each `RouterSpec` is a baklava DSL spec that describes the routes' inputs, outputs, and example bodies; running `sbt test` produces:

- `target/baklava/openapi/openapi.yml` — the OpenAPI spec
- `target/baklava/swagger-ui/` — a static Swagger UI bundle pointing at the spec
- `target/baklava/tsrest/` — a TypeScript ts-rest contract package

In dev (`app.environment = "dev"` in config), the app serves the same artifacts at:

- `/openapi/openapi.yml`
- `/swagger` — the Swagger UI
- `/docs` — alias

If the dev URLs return empty, run `sbt test` once to regenerate; the artifacts are checked into `target/`, not committed.

## Admin endpoints

Everything under `/admin` is gated by HTTP Basic auth using credentials from the `admin.user` / `admin.password` config (set via env vars in production). Out of the box:

- `/admin/health-check` — deeper probe than the public `/v1/health-check`; checks Postgres + SMTP from `HealthCheckModule`.
- `/admin/jobs` — read-only scheduler dashboard listing registered tasks and queued/running rows; provided by `SchedulerAdminRouter`.

Admin is not a module — `ApplicationLoader` gates the whole subtree once and modules contribute via the same `route` method (the `/admin` routes live on `ApplicationLoader` itself for now, since they're cross-cutting).

## Rate limiting

Routers that need request-rate caps mix in `RateLimitDirectives` and use `rateLimited(name, to, within, by = …)` around a `complete`. The `name` is the bucket key; `to` is the burst, `within` the window; `by` keys per-user (`_ => "user:..."`) or per-IP (default). Implementation lives in `RateLimiterRuntime` and is in-memory by default — see [rate-limiting.md](rate-limiting.md) to swap it for Redis.

## Pagination

List endpoints are offset-paginated. The reusable pieces are in `madrileno.utils.http`: `Limit` / `Offset` (validated opaque types), `SortDirection` (`Asc` / `Desc`), `PageRequest[F]` (limit + offset + a `sortBy: F` field-enum + direction), and the response envelope `Page[A] { items, total, limit, offset }` (`derives Encoder.AsObject, Decoder` — generic, like `Error[T]`).

`GET /v1/auctions` is the worked example: `?limit=` (1–100, default 20, out-of-range values are clamped — not rejected), `?offset=` (default 0), `?sortBy=` (a per-endpoint enum — `CreatedAt | EndsAt | StartingPrice` here, default `CreatedAt`), `?sortDir=` (default `Desc`). An unknown `sortBy`/`sortDir` value is a 400 (kebs derives the param codec from the enum). The repository appends the primary key (`id ASC`) as a tie-break to whatever sort the client picked — without it, paging silently skips or duplicates rows when the sort keys tie. The total is one extra `COUNT(*)` with the same `WHERE`; the client derives `totalPages` / `hasMore` from `total`, `limit`, `offset`.

On the repository side, `madrileno.utils.db.dsl` carries the pieces: a row filter that mixes in `PageableSqlFilter` holds an `Option[PageRequest[ThatSortField]]` field and implements `sortColumn` (its sort-enum → `Column` mapping), `tieBreakColumn` (the primary key), and `offsetLimit` (from the page) — `PageableSqlFilter` turns those into the `ORDER BY <col> <dir>, <pk> ASC` and `OFFSET ? LIMIT ?` SQL; the helpers `orderByColumns(...)` / `offsetLimitClause(...)` build the clauses, and `repository.countByFilter(filter)` does the total. `repository.findPageByFilter(filter)` returns `(rows, total)` for a plain `SELECT … WHERE … ORDER BY … OFFSET … LIMIT` — so a new paginated list endpoint needs no hand-rolled SQL at all. The auctions endpoint is the exception: `AuctionRepository.list` hand-writes its `SELECT` because of the `currentPrice` `LEFT JOIN LATERAL`, but it still reads `filter.orderByFragment` / `filter.offsetLimitFragment` (the `PageableSqlFilter` hooks) and calls `repository.countByFilter(filter)` — no ad-hoc ordering/paging SQL. (A row filter that mixes in `PageableSqlFilter` and carries an extra `page` field can't use the `SqlFilterDerivation.filterFragment(this, cols)` macro — that needs a `Mirror.ProductOf` over exactly the predicate fields — so it writes `filterFragment` via `fromPredicates((pred -> col, …))`, the lower-level primitive.)

**Offset, not cursor — for now.** Offset is right for a catalogue ("jump to page 4"). It has two known weaknesses: deep offsets are O(offset) (irrelevant until the table is large *and* clients page far), and concurrent inserts can shift rows between pages (mildly annoying for infinite scroll, invisible for a paged grid). The fix where it matters — high-write feeds, e.g. bid history — is keyset/cursor pagination (`WHERE (sortKey, id) < (?, ?)`, response `{ items, nextCursor }`, no `total`). `Page[A]` and a future `Cursor[A]` are deliberately separate types so neither constrains the other.

## Testing routes

Two flavours:

- **Service-level specs** (e.g. `AuctionImageServiceSpec`) test the IO logic directly with a real Postgres + in-memory `ObjectStore`, no HTTP layer. Fast, no marshalling concerns.
- **Router specs** use baklava's DSL to describe and test the route at the HTTP entrypoint. These are also what produces the OpenAPI spec, so writing a router spec is also writing the public API documentation. See [testing-guide.md](testing-guide.md).

## Where to look next

- [json.md](json.md) — codec derivation, opaque-type and enum codecs.
- [auth.md](auth.md) — how `AuthContext` arrives at `authedRoutes`.
- [error-handling.md](error-handling.md) — Result-ADT-per-method convention, status mapping.
- [rate-limiting.md](rate-limiting.md) — `RateLimitDirectives` mechanics.
- [websockets.md](websockets.md) — `wsRoutes` and the WebSocket builder.
