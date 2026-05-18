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

## More to come

Scaffolding (`scaffold-module.scala`) and an MCP server exposing project docs as tools are on the backlog. Each will live under `scripts/` with its own section in this doc.
