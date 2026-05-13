# TODO

Working backlog for DX, missing-vs-mainstream-framework gaps, and follow-up cleanup. Not committed; pick up when ready.

Tiers are *user-impact*, not effort. `[S]` = small (hours), `[M]` = medium (1–2 days), `[L]` = large (week+).

---

## Tier 0 — first-clone DX cliff (highest ROI, smallest effort)

These are the things that make a new user bounce in their first 30 minutes.

- [ ] **Stub Firebase so the app boots with default `.env`.** `[S]` Currently `FIREBASE_KEY='{}'` crashes startup. Either soft-fail Firebase init with "Firebase disabled in dev" or ship a no-op `ExternalAuthVerifier` selected when `firebase.key` is the literal `{}`.
- [x] **Warn on pending migrations at dev startup.** ✅ done (PR #42) — reshaped from "auto-migrate" (auto-applying on `~reStart` crash-loops the app while you edit a `.sql`). When `app.environment == "dev"`, `Migrations.warnIfPending(pgConfig)` runs `Flyway.info()` and logs a one-line warning if anything's pending — best-effort, never fails startup; prod untouched. Turns the cryptic `relation "x" does not exist` on request #1 into a clear boot-time message.
- [x] **Placeholder for `/swagger` before tests run.** ✅ done (PR #42) — `ApplicationLoader.baklavaDocs` serves a small HTML page ("run `sbt test` once") for `GET /swagger` when `target/baklava/openapi/openapi.yml` doesn't exist yet.
- [ ] **Add `html/` to `.gitignore`.** `[XS]` Baklava test output appears as untracked every `git status`.
- [~] **`Makefile` (or `scripts/`).** ~~won't do~~ — Makefiles for backend dev are out of fashion; the sbt commands are documented in `dev-workflow.md` and that's enough. (If a task-runner is ever wanted, `just` over `make`.)

## Tier 1 — gaps every framework comparison flags

- [ ] **Scaffolding CLI / generators.** `[L]` `rails g scaffold`, `mix phx.gen.json`, `nest g resource`. Most impact: `sbt newModule <name>` that materializes the 7 files + migration + spec stubs from `adding-a-module.md`. The doc is 1000+ lines because there's no generator; turn the prose into code.
- [ ] **REPL with `ApplicationLoader` pre-wired.** `[M]` `rails console`, `iex -S mix phx.server`. Drop into ammonite/scala-cli with `application.auctionService` etc. in scope. Killer DX feature once it works.
- [x] **CORS module.** ✅ done — http4s `CORS` in `Main` behind `cors.enabled`; `cors.allowed-origins` empty ⇒ any origin in dev / `base-url` host otherwise (`${?CORS_ALLOWED_ORIGINS}` to override); no credentials (Bearer auth). `Cors.policy`/`CorsConfig` + `CorsSpec` + `docs/http.md` `## CORS`. (FE #17.)
- [ ] **DB seed mechanism.** `[S]` `sbt seed` task running `Seeds.scala` against the dev DB. Mirror Rails `db:seed`.
- [x] **Pagination helper.** ✅ done (offset) — `Limit`/`Offset`/`SortDirection`/`PageRequest[F]`/`Page[A]` in `madrileno.utils.http`, worked through `GET /v1/auctions` (`?limit=&offset=&sortBy=&sortDir=`, `Page[AuctionDto]` envelope, `id`-ASC tie-break, sibling `COUNT(*)`); `## Pagination` doc in `http.md`. (FE #8.) Cursor variant (`{ items, nextCursor }`, keyset) for the bid feed is a separate follow-up.

## Tier 2 — production essentials missing

- [ ] **Graceful shutdown.** `[M]` Explicit SIGTERM handler: stop accepting → drain in-flight → close DB pool → flush OTel exporters. Rolling deploys currently cut active requests.
- [ ] **Idempotency-key middleware.** `[S]` Stripe-style: header `Idempotency-Key: <uuid>` cached for 24h with response snapshot. Essential for any side-effectful POST.
- [ ] **Feature-flag trait + DB-backed implementation.** `[S]` `FeatureFlag.isOn("name", userId): IO[Boolean]`. Even minimal is enough — projects need it as soon as they ship to multiple environments.
- [~] **`X-Request-Id` middleware.** Low value — likely won't do. `traceparent` (W3C trace context) already serves this; the LB can map it to `X-Request-Id` for legacy consumers if needed, so there's not much to gain from a dedicated middleware.
- [ ] **Refresh-token expiry.** `[S]` Add `expires_at` column + check in `RefreshToken.isValid` + read `refresh-token.valid-for` config (recently removed; reintroduce when this lands). Current behavior: leaked unused token = forever-valid until revoked.
- [ ] **API versioning strategy beyond hardcoded `/v1`.** `[M]` Document the path-prefix + content-negotiation options; pick one. Currently the `/v1` lives in `application.conf` with no plan for `/v2` in parallel.

## Tier 3 — admin / operations affordances

- [ ] **`/admin/loggers`.** `[S]` Spring-Actuator-style runtime log level toggling. Logback supports it natively.
- [ ] **`/admin/config`.** `[S]` Show non-secret config (with explicit redactor for known-secret keys). Useful for debugging "what env did this pod see."
- [ ] **`/admin/routes`.** `[S]` List every registered route + auth requirement + rate limit. http4s/stir don't expose this directly; we'd walk our own provider chains.
- [ ] **Actionable `/admin/jobs`.** `[M]` Today read-only. Add `POST /admin/jobs/{name}/{instance}/retry` and `/discard`. Sidekiq-grade.
- [ ] **`/admin/threaddump` and `/admin/heapdump`.** `[S]` Standard JVM dumps. Saves 3am debugging.
- [ ] **`/admin/db` connection-pool view.** `[S]` Pool size, active, idle, wait time. Otel4s already emits the metrics; this is a UI on top.

## Tier 4 — auth / external

- [ ] **OAuth2/OIDC client beyond Firebase.** `[L]` Generic OIDC implementation of `ExternalAuthVerifier` + a config-driven provider list (Google, GitHub, etc.). Many people won't touch Firebase.
- [ ] **CSRF protection.** `[S]` Only matters if you serve HTML or accept browser form submissions; document the gap and ship a middleware behind a flag.
- [ ] **Outbound circuit breaker.** `[M]` Wrap sttp with failsafe/resilience4j-style breaker. Currently we have timeout + fold-to-domain; no breaker.

## Tier 5 — dev-mode affordances

- [ ] **Silent module-wiring detection.** `[M]` Forget to mix a module into `ApplicationLoader` → app compiles, module silently inactive. CI check or a `verifyAll` step that errors on this.
- [ ] **N+1 detection in dev.** `[M]` otel4s span analysis on Skunk queries; if N small queries hit the same table within one request, log a warning. Doable but not trivial.
- [ ] **`SBT_TPOLECAT_DEV` flip the default.** `[S]` Make CI-strict the default; have `dev` mode be opt-in. Currently the reverse, and people push code that fails CI.
- [ ] **Live config reload in dev.** `[M]` fs2 watch on `application.conf`; refresh `ConfigSource` and re-derive the dependent givens. Not all config can hot-reload (DB pool) but most can.
- [ ] **Pretty dev error pages.** `[S]` Currently a 500 with trace-id. Add an HTML page with stack-trace context, the request payload, and a link to OpenObserve when `app.environment = "dev"`.
- [ ] **Sample-data seed for dev.** `[S]` On first boot, populate a handful of auctions + bids + users so the app isn't empty. Behind a flag.

## Tier 6 — bigger swings (positioning-changing)

- [ ] **LiveView-equivalent / Inertia / HTMX adapter.** `[XL]` Real differentiator if built on the WebSocket primitives already shipped. None of the Scala ecosystem has a great answer here.
- [ ] **GraphQL story.** `[L]` Caliban or Smithy4s + GraphQL. Document the integration point even if not shipped.
- [ ] **Frontend starter consuming ts-rest contracts.** `[M]` Tiny Next.js or Astro `frontend/` directory wired against Baklava-generated contracts. Closes the loop.
- [ ] **Webhook-out dispatcher utility.** `[M]` "POST this URL when X happens, retry with backoff, dead-letter after 24h." Scheduler-backed; bundle as a utility rather than have everyone write it.
- [x] **Single-shot migrations container.** ✅ done — `madrileno.main.MigrateMain` (`IOApp` running Flyway against the app's `PG_*` config); native-packager emits `bin/migrate-main` in the same image; `Compile/mainClass` pinned to `Main` so `~reStart` stays unambiguous; flyway deps moved to compile-scope; docs in `dev-workflow.md` / `deployment.md`. Tested end-to-end via the Docker image against a fresh Postgres.

## Tier 7 — i18n / content / messaging

- [ ] **Real i18n infrastructure.** `[M]` Replace exhaustive `Language` match in templates with a `t("key", lang)` lookup. Don't reach for ICU MessageFormat unless multilingual matters.
- [ ] **Inbound email (Action Mailbox equivalent).** `[L]` Parse incoming mail, dispatch to handlers. Niche but Rails has it; sometimes asked for.
- [ ] **Email bounce / opt-out handling.** `[M]` Webhook from SES/SendGrid for bounces, mark address as inactive, suppress sends. Production-mailer concern.
- [ ] **List-Unsubscribe headers + opt-out audit.** `[S]` Required for production marketing mail.

## Doc follow-ups (after the docs PR lands)

- [ ] **Use-the-docs walkthrough.** `[M]` Follow `adding-a-module.md` literally; log every friction. The friction list is the next docs round.
- [ ] **CI link-checker.** `[S]` GitHub Action that runs the `grep` link-validator from the docs verification round. Catches broken internal links on PR.
- [ ] **Ruthless prose pass.** `[M]` Pre-existing docs (`adding-a-module.md`, `file-storage.md`, `imaging.md`, `testing-guide.md`) had less scrutiny than mine. They should get the same audit + edit. Same for the "Where to look next" footers — trim where they don't earn their keep.
- [ ] **Mention REFRESH_TOKEN cleanup in CHANGELOG / migration notes** when you publish (so users who copied `.env` from history don't get confused).

## Quick wins to ship in one afternoon

If you want to start small, the highest-impact bundle that ships in a few hours:

1. Stub Firebase init for the `{}` case (~20 min) — app crashes on default `.env` otherwise
2. `/admin/loggers` (~1 hour via Logback's JMX or a direct route)

(Done: pending-migration warning + `/swagger` placeholder — PR #42; CORS module — PR #37. Dropped: `Makefile` and `X-Request-Id` — won't do, see above; `.gitignore html/` — leave `.gitignore` alone, `git rm --cached` if it nags.)
