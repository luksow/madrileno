# Dev workflow

The README's Quick Start gets you to a running app. This is everything past that — how to stay productive while writing code.

The whole loop is driven from sbt. Two ways to drive it.

## sbt shell vs `sbt --client`

**Long-lived shell** — open `sbt`, leave it running:

```
$ sbt
> ~reStart
```

This is the default for active development. Compilation is incremental, the JVM stays warm, classpath resolution is cached. First command takes 20–30 seconds; everything afterwards is fast.

**`sbt --client`** — connect to the same running server from another terminal:

```bash
sbt --client compile
sbt --client "testOnly madrileno.auction.services.AuctionServiceSpec"
sbt --client scalafmtAll
```

Use this when you have an sbt shell open in one terminal and want to run a one-shot command in another. Falls back to starting a server if none exists, so the first invocation is slow; everything after is sub-second. The CLAUDE.md tells the assistant to use `--client` so it doesn't spin up a new sbt every time.

**Plain `sbt <command>`** — starts sbt, runs the command, exits. Slow (full classpath resolution on every invocation). Reserve for shell scripts and CI.

## Watch loops

`~<task>` runs the task whenever a watched source changes:

| Command         | What it does                                                                  |
| --------------- | ----------------------------------------------------------------------------- |
| `~reStart`      | Recompile, kill the old app, start a new one. The default dev loop.           |
| `~test`         | Recompile, run the full test suite. Useful when refactoring across modules.   |
| `~testOnly Foo` | Like `~test` but scoped to one spec (or a glob: `*AuctionService*`).          |
| `~compile`      | Just type-check. Fast feedback when you don't care about test outcomes yet.   |

Watch is on Scala/SBT/HOCON files only — not `.env`, not docker-compose state. Restart sbt for those.

`reStart` (no `~`) is a one-shot run. Use it when you want to launch the app once for a manual smoke test without the rebuild-on-save behavior.

## Running tests

```
> test                                     // full suite (~7s on a warm JVM)
> testOnly madrileno.auction.services.AuctionServiceSpec
> testOnly *Auction*                       // glob matches multiple specs
> testOnly *AuctionServiceSpec -- -z "closes auction"   // one test by name substring
> testOnly *AuctionServiceSpec -- -t "exact test name"  // exact match
> testQuick                                // re-run only failed/never-run since last `test`
```

The `--` separates sbt args from scalatest args. `-z` is substring match on the test name; `-t` is exact match. Useful when you've broken one test and don't want to wait for the whole suite.

The `Test/test` task triggers Baklava generation as a side effect — the OpenAPI spec at `/swagger` is regenerated from `*RouterSpec` files every time tests run. If `/swagger` looks stale, rerun `test`.

Tests use Testcontainers for Postgres / Mailpit / MinIO — they spin up their own containers, so the docker-compose dev stack and the test run don't share state. Telemetry is stubbed with noop tracer/meter in tests; there's no OpenObserve container.

## Code quality

Run before every commit (the assistant does this automatically per CLAUDE.md):

```
> scalafmtAll                              // format every Scala file
> scalafixAll                              // run scalafix rules (organize imports, etc.)
```

Both are idempotent and fast. `scalafmtCheckAll` is the read-only version — fails if anything is unformatted. CI runs the check variant.

The build defines `verifyAll` as a CI proxy:

```
> verifyAll
```

This runs `scalafmtSbtCheck` + `scalafmtCheckAll` + `compile` + `test` in sequence — the same gates CI runs. If `verifyAll` passes locally, your push will pass CI (modulo flaky tests).

## Compiler strictness

The build uses `sbt-tpolecat`. In CI, `-Werror` is on: any warning fails compilation. Locally, `SBT_TPOLECAT_DEV=true` (set in `.env.sample`) downgrades warnings to warnings, so `~reStart` doesn't blow up every time you have an unused import in flight.

The cost: code that compiles locally can fail in CI on a warning you didn't see. Two ways to catch it before pushing:

- Run `verifyAll` (uses dev mode, but the test/compile output prints all warnings).
- Temporarily unset the flag:

  ```bash
  SBT_TPOLECAT_DEV= sbt --client compile
  ```

  This reproduces CI's strictness without committing a change.

The auto-memory has a note about this — `werror_in_ci` — because it's bitten more than once.

## Database tasks

Apply pending migrations:

```
> runMain madrileno.main.MigrateMain
```

That's the app's own `IOApp` — the same one the Docker image ships as `bin/migrate-main` for production deploys ([deployment.md](deployment.md)) — so it reads `application.conf` with `.env` already injected (`PG_HOST` / `PG_PORT` / `PG_DATABASE` / `PG_USER` / `PG_PASSWORD`).

The `flyway-sbt` tasks are also there for inspection / cleanup:

```
> flywayInfo                               // list applied / pending / out-of-order
> flywayValidate                           // verify checksums match the files
> flywayClean                              // DROP every table; only on a dev DB
```

There's a `flywayMigrate` too, but it evaluates `sys.env` at build-load time — *before* sbt-dotenv injects `.env` — so it only sees `PG_*` if your shell already has them. Use `runMain madrileno.main.MigrateMain` unless you have a reason not to.

Run a migration after:
- Adding a migration under `src/main/resources/db/migration/`.
- `docker compose down -v` (which wipes the volume).

The app does **not** auto-migrate on startup. Migrations are an explicit deploy step; running them by accident on prod has bitten people.

## IDE setup

- **Metals (VS Code)** — `Build: Import build` once after cloning. Metals reads the bloop config sbt generates. After adding a dependency in `build.sbt`, run `Build: Import build` again (or use the `import-build` MCP tool if you're driving via the assistant).
- **IntelliJ** — File → Open → select the project root → "Open as sbt project". Let it sync. Re-sync after dependency changes.

Either way, sbt itself doesn't need to be running for the IDE to work — they read the build separately.

## Common stuck states

**`~reStart` doesn't see a file change.**
Sbt's file watcher occasionally drops events on case-only renames or rapid saves. Hit Enter in the sbt shell to force a re-trigger; if that fails, exit (`exit` or Ctrl-D) and re-launch.

**Compile errors that don't go away after fixing the source.**
Stale incremental compile state. Run `clean` then `compile`. If it still fails, kill any running sbt server (`pkill -f sbt-launch` or remove `~/.sbt/1.0/server/`) and start fresh.

**`.env` change not picked up.**
The dotenv plugin reads `.env` once at JVM startup. Restart sbt.

**A test that passes locally fails in CI on a warning.**
You're hitting the dev-mode tpolecat issue above. Reproduce with `SBT_TPOLECAT_DEV= sbt --client compile`.

**A migration fails with a checksum mismatch.**
You edited a migration that's already been applied. Either roll back the file change or `flywayClean` your dev DB and re-migrate (only on dev — never on shared environments).

**OpenObserve traces tab is empty.**
Streams are created on first ingest; hit the app a few times, refresh, give it a moment. If it's still empty, check `OTEL_EXPORTER_OTLP_*` in `.env`.

**`/v1/auth/firebase` returns 503 (`provider-unavailable`).**
Firebase auth is only enabled when `FIREBASE_PROJECT_ID` is set; the stock `.env` leaves it blank, so the app boots but Firebase login is off. Set it to your Firebase project id — or, in dev, use `POST /v1/auth/dev` with `{"email":"you@example.com"}` (see below), which logs you in (creating the user) with no provider config at all.

**`/v1/auth/dev` returns 404 (`unknown-provider`).**
Dev login is explicit opt-in, gated on `DEV_AUTH_ENABLED=true` (default `false` in `application.conf`; the stock `.env` sets it). Set it to `true` in your local `.env` if you need the route active. **Never enable in deployments that face real users** — `DevAuthVerifier` mints a session for any email-shaped string, no credential check.

## One-shot recipes

```bash
# full clean validation, no warm cache
sbt --client clean compile test

# run only auction-related specs
sbt --client "testOnly *Auction*"

# run a single test by substring
sbt --client "testOnly *AuctionServiceSpec -- -z 'closes auction'"

# regenerate OpenAPI without running every test
sbt --client "testOnly *RouterSpec"

# wipe dev DB and start over
docker compose down -v && docker compose up -d && sleep 2 && sbt --client "runMain madrileno.main.MigrateMain"
```

## Adding a dependency

1. Edit `build.sbt`.
2. In Metals: `import-build`. In IntelliJ: re-sync. In sbt: the running shell picks up `build.sbt` changes on the next command (it'll print `[info] build.sbt has been changed; loading new project ...`).
3. Compile to confirm resolution succeeds.
4. The `find-dep` and `inspect` MCP tools (if you're driving via the assistant) are quicker than browsing Maven Central manually.

## Where to look next

- [configuration.md](configuration.md) — `.env` mechanics, why restarting sbt picks up changes.
- [database.md](database.md) — what migrations look like, how to add one.
- [observability.md](observability.md) — OpenObserve URLs, what each tab shows.
- The root [`README.md`](../README.md) — first-touch Quick Start, dev-stack ports, gotchas.
