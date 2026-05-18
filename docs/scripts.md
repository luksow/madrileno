# Scripts

scala-cli scripts under `scripts/` for project lifecycle tasks. Each is self-contained — its `//> using` directives declare deps, scala-cli does the rest. Run them either as executables (`./scripts/X.scala …`) or via scala-cli (`scala-cli run scripts/X.scala -- …`).

## `init-project.scala`

Use this once, right after cloning the template, to turn it into your project. Renames `madrileno` everywhere, swaps the Scala package, drops the auction showcase domain.

```bash
./scripts/init-project.scala wine-cellar
./scripts/init-project.scala wine-cellar --package winecellar
```

If you don't pass `--package`, the package is the project name lowercased with non-alphanumerics stripped (`wine-cellar` → `winecellar`).

What it does, in order:

1. **Sanity checks** — refuses to run if `build.sbt` isn't present or if `src/main/scala/madrileno/` is missing (already renamed).
2. **Deletes the auction demo** — `src/{main,test}/scala/madrileno/auction/` and any Flyway migration whose filename mentions `auction` or `bid`.
3. **Rewrites file contents** under every text file outside `.git`, `target`, IDE caches, `node_modules`, and `scripts/`:
   - Drops blocks bracketed by `// scripts:auction-block-start` / `// scripts:auction-block-end` — auction-coupled fragments in test support that can't be deleted as whole files (today: two in `TestData.scala`, one in `TestApplicationLoader.scala`, one in `SchedulerAdminRouterSpec.scala`). The markers must occupy their own line — inline mentions in prose are ignored.
   - Drops `import madrileno.auction.*` lines and `with AuctionModule` lines from the loaders' `extends` chains.
   - Renames `madrileno.<X>` package references to `<package>.<X>`.
   - Renames standalone `madrileno` to `<project-name>` (HOCON `app.name`, container names, `OTEL_SERVICE_NAME`, `PG_DATABASE`, `MAILER_FROM_ADDRESS`, README/doc references, etc.).
4. **Renames the source directories** — `src/{main,test}/scala/madrileno/` → `src/{main,test}/scala/<package>/`.

After running:

```bash
cp .env.sample .env
sbt 'scalafixAll; scalafixAll; test'
```

`scalafixAll` runs twice on purpose. The auction surgery leaves a pile of imports that were used only by the now-deleted methods (e.g. `org.http4s.MediaType`, `madrileno.utils.imaging.*`); the first pass removes the obvious ones; some others only become unused once the first pass has run (cascading). The second pass picks those up. After that, `test` should be green.

If anything's off, `git checkout .` reverts.

### About the marker convention

`// scripts:auction-block-start` … `// scripts:auction-block-end` flags a region that's coupled to the auction demo but can't be deleted as a standalone file (e.g., one method inside a shared `TestData.scala`). The markers are just comments — they have no effect at compile time and live in the source repo permanently. `init-project.scala` deletes whatever's between them (markers included) when it runs.

Adding a new auction-coupled block elsewhere? Bracket it with the same markers and the init script picks it up automatically. Be conservative — every marker is a hard-coded "this is auction-specific, drop on init" declaration that has to stay accurate.

### What it doesn't do

- Doesn't touch your git working tree (no `git add` / `git commit`). Run `git status` after, review, commit yourself.
- Doesn't run `sbt compile`. The compiler is the safety net; running it is your call.
- Doesn't rewrite prose. Doc files get the `madrileno` → `<name>` substitution but `README.md`, badges, and any project-specific marketing copy are yours to rewrite. Same for `docs/architecture.md`-style "why we did X" notes — they describe the template's reasoning and might or might not match your own.
- Doesn't delete itself. Once you're done renaming, `rm scripts/init-project.scala` (and this doc, if you want) — the template baggage is yours to keep or trim.

## `scaffold-module.scala`

Generate a new module (aggregate vertical slice) under the project's package — domain, repository, service, router, DTO, module trait, Flyway migration, and three specs (domain, repository, router). Wires the module into `ApplicationLoader`'s `extends` chain automatically.

```bash
./scripts/scaffold-module.scala Wine wines
```

Two positional arguments:

1. **Aggregate** — PascalCase singular, drives class names: `Wine`, `WineId`, `WineRepository`, `WineModule`, etc.
2. **Plural** — lowercase plural, drives the SQL table name (`wines`) and the URL segment (`/v1/wines/{id}`).

The script derives the singular lowercase variant from the aggregate (`Wine` → `wine`) and uses it for the module subpackage name and variable names. The project's root package is auto-detected from the single directory under `src/main/scala/`.

What it does:

1. **Sanity checks** — refuses to run if not in a project root (no `build.sbt` / no `src/main/scala/`), if the project package can't be uniquely identified (must be exactly one directory under `src/main/scala/`), if the templates dir (`scripts/templates/module/`) is missing, if the migration dir (`src/main/resources/db/migration/`) is missing, if `ApplicationLoader.scala` is missing or doesn't contain the expected `HealthCheckModule` anchors, if either target dir (`mainDest`, `testDest`) already exists, if a `<Aggregate>Module.scala` already exists anywhere under `src/main/scala/` (class-name collision with an existing module — e.g. `HealthCheck health_checks` would clash with the built-in `HealthCheckModule`), or if any existing migration already creates a table with the plural's name (e.g. `Foo auctions` when an earlier migration already creates `auctions`). All checks run **before** any writes, so a failing precondition leaves the working tree untouched.
2. **Copies the template tree** with placeholder substitution. Files under `scripts/templates/module/main/` go to `src/main/scala/<package>/<aggregate>/`; `test/` goes to `src/test/scala/<package>/<aggregate>/`; `migration/` files become `V<next>__<name>.sql` under `src/main/resources/db/migration/`, where `<next>` is the highest existing `V<N>` plus one.
3. **Auto-wires** by inserting both `import <package>.<aggregate>.<Aggregate>Module` and `    with <Aggregate>Module` into `ApplicationLoader.scala`, anchored on the `HealthCheckModule` import and `with` clauses (always present in the framework's stock loader). Imports are not sorted alphabetically at insertion — `sbt scalafixAll` (recommended in the next-steps printout) reorders them.

After running:

```bash
sbt 'compile; scalafmtAll; scalafixAll'
```

`compile` verifies, `scalafmtAll` formats the generated files, `scalafixAll` reorders the auto-wired import in `ApplicationLoader.scala` (which the script inserts in a fixed position rather than guessing alphabetic order). The generated module compiles green out of the box: `id: <Aggregate>Id`, `name: <Aggregate>Name` (an opaque type over `String` with a non-empty validation), plus the standard audit columns (`createdAt`, `updatedAt`, `deletedAt`) on the row side. The `name` field is a starter — feel free to delete or rename. Add more domain fields by editing the case class, the `Row`, the `Table` mapping, the DTO, and the migration in lockstep.

### Placeholder substitution

The templates use these placeholders (substituted both in filenames and contents):

- `__Aggregate__` → the PascalCase argument (`Wine`)
- `__Aggregates__` → capitalized plural, derived from the plural argument (`Wines`) — used for OpenAPI tags
- `__aggregates__` → the plural argument (`wines`)
- `__aggregate__` → the lowercase singular, derived (`wine`)
- `__package__` → the auto-detected project package (`madrileno` in the template, your own name after `init-project.scala`)

Substitution order is preserved (longer plural forms before shorter singular forms), but with the current placeholders the order is cosmetic — the trailing `__` on each token means `__aggregate__` is not a substring of `__aggregates__`, so neither shadows the other regardless of pass order. The convention is there as a habit if you add overlapping placeholders later.

### What it doesn't do

- Doesn't generate a service spec. The service is a thin wrapper around the repository in the scaffold; add a spec when there's real service logic.
- Doesn't enforce ownership / authorization. The generated router takes the `AuthContext` (so the route is gated by the framework's auth gate) but doesn't yet scope queries by `authContext.userId` — that's where you fill in the domain rules.
- Doesn't run `sbt compile`, `scalafmtAll`, or `scalafixAll`. All three are bundled into one command in the next-steps printout; `scalafixAll` is the one that sorts the auto-wired import in `ApplicationLoader.scala`.
