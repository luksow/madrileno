# Observability

Three signals — **traces, metrics, logs** — all flow through OpenTelemetry to a single OTLP receiver. In dev the receiver is OpenObserve, started by `docker compose up -d`; in production it's whatever OTLP-compatible backend you point the SDK at (Honeycomb, Grafana, Datadog, your own collector). The application doesn't know or care; it talks OTLP.

The integration is wired once in `Main` and threaded through the rest of the app as a `TelemetryContext` given. Most code never touches the SDK directly — the inbound HTTP middleware, outbound HTTP middleware, Skunk's Postgres driver, and the logback OTel appender already cover the common cases.

## TelemetryContext

```scala
final case class TelemetryContext(
  meter: Meter[IO],
  tracer: Tracer[IO],
  underlying: OpenTelemetry
)
```

- `tracer` and `meter` are otel4s wrappers — cats-effect-friendly, `IO`-typed APIs over the Java SDK. Use these from application code.
- `underlying` is the raw Java SDK. Used when interop demands it (the http4s server middleware and the logback appender both want a Java `OpenTelemetry`).

`Main` constructs one `TelemetryContext` from `OtelJava.autoConfigured[IO]()` and makes it a `given` for the rest of the wiring. Modules accept it the same way they accept any other capability:

```scala
trait AuctionModule {
  given telemetryContext: TelemetryContext
  // …
}
```

Any class that needs spans, meters, or trace-aware logging takes `(using TelemetryContext)` — `LoggingSupport`, `BaseRouter.error`, `Handlers`' exception/rejection paths, `OpenTelemetryTracingBackend`, all of them.

## What's instrumented automatically

You don't need to call `tracer.span(...)` for these — the middleware does it.

| Layer                                | What ships                                                                                       |
| ------------------------------------ | ------------------------------------------------------------------------------------------------ |
| **Inbound HTTP** (http4s server)     | `ServerMiddleware` produces a span per request and `OtelMetrics` produces request-count/duration histograms. Configured in `Main`. |
| **Outbound HTTP** (sttp)             | `OpenTelemetryTracingBackend` + `OpenTelemetryMetricsBackend` wrap the shared `httpClient`. Every external API call is a child span and contributes to outbound HTTP metrics. |
| **Postgres** (Skunk)                 | `PgTransactor.resource(...)(using Tracer[IO], Meter[IO])` — Skunk's built-in otel4s integration spans every query and emits pool metrics. |
| **Logs**                             | `OpenTelemetryAppender` (configured in `logback.xml`) ships every logback event as an OTLP log record. `OpenTelemetryAppender.install(otel.underlying)` connects the appender to the SDK in `Main`. |

Net result: with no instrumentation in your code, you already get one span per inbound request, child spans for every outbound call and every DB query, latency/error metrics on the HTTP boundary, and structured logs correlated by trace-id. Add `(using TelemetryContext)` and call `tracer.span(...)` only when you want extra granularity.

## Per-request span enrichment

`ApplicationLoader.routes` adds `app.user.id` to the inbound HTTP span on the authenticated branch:

```scala
onSuccess(telemetryContext.tracer.currentSpanOrNoop.flatMap(
  _.addAttribute(Attribute("app.user.id", auth.userId.toString))
)) { … }
```

`currentSpanOrNoop` returns the active span (or a no-op if none). The attribute lands on the request's root span, which means traces search and group-by both work — pull every trace from a user, or aggregate latency per user when a single account complains.

Use the same pattern when you want a request-wide attribute on the active span — workspace ID, feature flag, request source. Anything cheap to compute and high-cardinality-but-bounded (i.e. per-user / per-tenant, not per-request-body). Keep the `app.*` namespace prefix for app-specific attributes; OTel reserves the unprefixed names for its semantic conventions.

## Trace-id correlation

Every log line includes the active trace context — `trace_id` / `span_id` / `trace_flags` — via logback's MDC:

```
%d{"yyyy-MM-dd HH:mm:ss.SSS"} %-5level %thread %logger{36} - [trace_id=%X{trace_id:--} span_id=%X{span_id:--} trace_flags=%X{trace_flags:--}] %msg%n
```

These are the field names the OTel Logs Data Model defines as the canonical correlation keys, so OpenObserve / Grafana / Datadog / Honeycomb auto-link logs to traces without extra configuration. (The W3C `traceparent` header is the wire format for HTTP propagation — different shape, different audience; we keep that one for outbound HTTP response headers, see below.) The `:--` is logback's default-value syntax — lines logged outside any trace context render `trace_id=-` instead of an empty `trace_id=`.

`LoggingSupport.logger` adds the MDC context before the underlying logger fires. Using a logger that closes over a `TelemetryContext`:

```scala
class AuctionService(...)(using TelemetryContext) extends LoggingSupport {
  def closeAuction(id: AuctionId): IO[Unit] =
    logger.info(s"Closing auction $id") *> …
}
```

The `info` call internally does:
1. `tc.tracer.currentSpanOrNoop.map(_.context)` — fetch the currently-active `SpanContext` from the otel4s `traceScope` (a `Local[IO, Context]` backed by an IOLocal holding the Java OTel `Context`). `tracer.currentSpanContext: F[Option[SpanContext]]` reads from the same `Local` per the otel4s source — both should produce equivalent results here. We use `currentSpanOrNoop` because it returns a `Span[F]` we can also pull attributes from when needed.
2. Build a `Map("trace_id" -> .traceIdHex, "span_id" -> .spanIdHex, "trace_flags" -> .traceFlags.toHex)` — skipped when the context is invalid (no active span, e.g. a noop tracer in tests).
3. Hand that map to log4cats as the structured context.
4. log4cats writes those keys into the SLF4J MDC.
5. The MDC values land in the log line via the `%X{...}` pattern *and* are captured as OTLP log-record attributes by `OpenTelemetryAppender` (`<captureMdcAttributes>*</captureMdcAttributes>`).

`ApplicationLoader.routes` also snapshots the trace context at routes entry and threads it into the `logAction` used by `logRequest` / `logResult`. The inbound-request log line is logged within the middleware's scope so the live read works; the response log line fires during response-body materialization, after the middleware's `Resource.use` has unwound and the trace IOLocal has reverted — at that point the snapshot is the only source. `LoggingSupport.propagateContext` merges `snapshot ++ live` (RHS wins), so nested spans still get their own `span_id` when live context is present.

Three knock-on effects:

- **Console/file logs include the trace context.** Grep a log line, get `trace_id=…`, jump straight to the trace in OpenObserve.
- **OTLP log records carry the trace context too.** OpenObserve renders logs and traces side-by-side because the three correlation fields land as attributes on every record — the names match what the backend expects.
- **Errors include the trace ID in the response body.** `BaseRouter.error` and `Handlers` populate the `Error.instance` URI as `trace-id:<hex>`. A frontend or curl user looking at a 500 can paste that into OpenObserve.

For outbound HTTP, the `traceparent` W3C header is still injected via `tracer.propagate(Headers.empty)` (see `ApplicationLoader.routes`) so downstream services can resume the trace.

There's a non-tracing variant for callers who don't have a `TelemetryContext`: `loggerWithoutTracing`. Use it sparingly — anything in a request path or task should have the context.

## Manual spans

When you want a span around a non-trivial chunk of work — a multi-step operation, a CPU-bound parse, anything that's worth a row in the trace — use `tracer.span`:

```scala
tc.tracer.span("AuctionService.closeAuction").use { span =>
  for {
    _ <- span.addAttribute(Attribute("auction.id", id.toString))
    auction <- repo.find(id)
    _ <- /* … */ IO.unit
  } yield ()
}
```

`Resource[IO, Span[IO]]` semantics — opening the span starts it, the `use` block runs in its scope, and the span closes (with its end timestamp set) when the block finishes or fails. Errors flowing through the `use` block automatically tag the span as failed.

Don't wrap *everything*. Lots of small spans inflate trace size and slow down the UI. Span the work you'd want to debug latency on; leave trivial calls inside their parent span.

`tracer.currentSpanOrNoop.map(_.context)` returns the current `SpanContext` (or one with `isValid == false` when no span is active) — useful when you want the trace ID for a log line or response header. `tracer.currentSpanContext: F[Option[SpanContext]]` reads from the same underlying `Local[F, Context]` and is fine to use too; pick whichever shape fits the call site.

## Manual metrics

Less common, but the API is symmetric. From a `(using tc: TelemetryContext)` scope:

```scala
counter <- tc.meter.counter[Long]("auction.bids.placed").create
// later
counter.inc(Attributes(Attribute("currency", currency.code)))
```

Histograms, gauges, observable instruments — all on the otel4s `Meter[IO]`. The SDK exports them via OTLP on the same connection used for traces and logs.

If you find yourself making a counter for "thing happened" alongside a `logger.info("thing happened")`, that's usually a sign you wanted a counter all along — log lines are expensive, metrics are cheap, and the dashboard is more informative than a grep.

## Route classification

Span names from the http4s middleware default to the URI path. That's terrible for trace search: `/auctions/abc-123-uuid/bids` and `/auctions/def-456-uuid/bids` would be two different operations.

`Main` configures a `RouteClassifier` that normalizes UUIDs and integer IDs in URL segments:

```scala
override def classify(request: RequestPrelude): Option[String] =
  request.uri.path.segments.foldLeft("") { (acc, seg) =>
    if (seg.encoded.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-…$")) acc + "/{uuid}"
    else if (seg.encoded.forall(_.isDigit))                       acc + "/{id}"
    else                                                          acc + s"/${seg.encoded}"
  }
```

The pattern is naive but fits the project's path conventions. If you start using slug-like identifiers (`/users/foo-bar-baz`) extend the matcher; otherwise every slug ends up its own span name.

## Header and query redaction

`ServerMiddleware` by default doesn't capture request/response headers or query strings. The build opts in:

```scala
ServerSpanDataProvider
  .openTelemetry(redactor)
  .withRouteClassifier(routeClassifier)
  .optIntoClientPort
  .optIntoHttpRequestHeaders(HeaderRedactor.default)
  .optIntoHttpResponseHeaders(HeaderRedactor.default)
```

`HeaderRedactor.default` ships with a sensible deny-list (Authorization, Cookie, Set-Cookie, etc.). The query/path redactor is `NeverRedact` — fine for an internal API where paths don't carry secrets. If you start putting tokens in URLs (don't, but if you must), implement a custom `QueryRedactor`.

## Logback config

`logback.xml` has three appenders:

- **Console** — `trace_id` / `span_id` / `trace_flags` in the pattern; what you read during dev.
- **File** — `logs/madrileno.log`, rolling daily / 64MB / 28 days. Local debugging only; production should rely on the OTLP shipping path instead.
- **OpenTelemetry** — `OpenTelemetryAppender` from `opentelemetry-logback-appender-1.0`. `<captureMdcAttributes>*</captureMdcAttributes>` so MDC keys (the three trace fields, plus anything else callers stash) become OTLP log attributes; `<captureExperimentalAttributes>true</captureExperimentalAttributes>` adds thread name, logger name, and similar metadata.

Root level defaults to `DEBUG`, override with `LOG_LEVEL` (env). Per-logger overrides go in `<logger name="..." level="..."/>` — there's a `WARN` for `org.apache.hc` to silence Apache HttpClient noise.

## Outbound-HTTP request/response logging

Separate from the OTel layer: sttp's `LoggingBackend` is also wired into the shared `httpClient`. It writes each request and response to the project logger using the configured level (`logging.loglevel-request-response` in `application.conf`, default 4 = `DEBUG`). With `logRequestBody = true, logResponseBody = true`, you get the full body in dev — invaluable for debugging gateway integrations. Drop the level (or set request/response body to `false`) before shipping to production unless you really want every JSON payload in your logs.

## Configuration

Everything is `OTEL_*` environment variables — see `.env.sample`:

```
OTEL_SDK_DISABLED=false
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_HEADERS="authorization=Basic …"
OTEL_SERVICE_NAME="madrileno"
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:55080/api/default/v1/traces"
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT="http://localhost:55080/api/default/v1/metrics"
OTEL_EXPORTER_OTLP_LOGS_ENDPOINT="http://localhost:55080/api/default/v1/logs"
```

`OtelJava.autoConfigured[IO]()` reads these directly — the app code doesn't parse OTLP config. Set `OTEL_SDK_DISABLED=true` to disable telemetry entirely (useful in load tests where you don't want to measure the exporter).

`build.sbt` adds `-Dotel.java.global-autoconfigure.enabled=true` so the autoconfigure module activates on startup.

The OpenObserve container in `docker-compose.yml` listens on `localhost:55080`; the unusual signal-specific endpoints are because OpenObserve requires the org segment in the path, which the OTLP SDK only allows when each signal has its own endpoint.

For production, point at your real backend:

```
OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io
OTEL_EXPORTER_OTLP_HEADERS="x-honeycomb-team=your-key"
```

(Real OTLP receivers accept the unified endpoint without the per-signal paths, so the simpler form works.)

## OpenObserve in dev

`docker compose up -d` starts it. Open `http://localhost:55080`, log in with `root@example.com` / `Complexpass#123`. Tabs:

- **Logs** — every log line that went through the OpenTelemetry appender. Filter by trace ID, level, MDC keys.
- **Traces** — span tree per request. Click an inbound HTTP span and you see the DB queries, outbound calls, custom spans inside.
- **Metrics** — http4s request count/latency, sttp outbound, Skunk pool stats. Build dashboards or ad-hoc PromQL queries.

The auth header in `OTEL_EXPORTER_OTLP_HEADERS` is base64 of the same login. Don't ship that.

## Where to look next

- [http.md](http.md) — `BaseRouter.error` shows how spans surface trace IDs in error bodies.
- [database.md](database.md) — Skunk's tracer/meter wiring; per-query spans in OpenObserve.
- [external-apis.md](external-apis.md) — outbound HTTP layering; every gateway call is a child span.
- [scheduler.md](scheduler.md) — task executions get spans from the same machinery; useful for diagnosing why a recurring task slowed down.
