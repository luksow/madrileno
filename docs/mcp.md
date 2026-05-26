# MCP server

`scripts/mcp-server.scala` exposes the upstream Madrileno reference (at a pinned commit) to an AI coding assistant via the [Model Context Protocol](https://modelcontextprotocol.io). The point: when you ask Claude to "add an `Appointment` module," it can read the canonical `user` and `auction` modules verbatim — opaque types, soft-delete idioms, router shape, spec patterns — and copy the non-obvious bits the scaffold doesn't generate.

It's optional. The template works without it. The MCP earns its keep when you're building new modules, want to learn a less-obvious framework convention, or want to pick up patterns from upstream that landed after you initialized your project.

## What it serves

Five tools, designed around the conceptual units of the codebase (a module, a doc, a path, a diff):

| Tool                       | Returns                                                                                    | Use when                                              |
|----------------------------|--------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `madrileno_overview()`     | Orientation: what madrileno is, reference modules, doc index, pinned ref                   | First call in any session — the anchor                |
| `madrileno_module(name)`   | Concatenated source of all main + test files under one module (`user`, `auction`, etc.)    | Learning a module pattern in full                     |
| `madrileno_doc(name)`      | One doc (markdown), e.g. `auth`, `domain-modeling`, `adding-a-module`                       | Expanding on a concept                                |
| `madrileno_source(path)`   | Verbatim file at the pinned ref. Fallback for specific paths                                | "Show me this exact file"                             |
| `madrileno_changes(since?, paths?, target?)` | `git log --oneline` between two refs, optionally path-filtered                | Pulling upstream changes since the project was anchored |

Source returned by `madrileno_module` and `madrileno_source` is **automatically rewritten** from `madrileno.*` to your project's package. Docs are returned verbatim.

## How the anchoring works

`init-project.scala` writes `.madrileno-ref` to your project root on init:

```
repo=<your origin URL>
ref=<sha at init time>
```

`repo=` is derived from `git remote get-url origin` (so it works for forks / SSH clones), falling back to the canonical upstream when there's no origin. `ref=` is the sha of HEAD at init time.

The MCP server reads this **once at startup** (lazy val) and anchors every tool call to that commit. If you edit `.madrileno-ref` while the server is running, restart the server for the change to take effect. Commit `.madrileno-ref` — collaborators benefit from a shared pin.

The MCP server keeps a local shadow clone of the upstream repo at `.madrileno-mcp/repo/` (gitignored). First launch clones (~50MB, one-time). Every launch does a `git fetch origin` so `madrileno_changes` can compare your pinned ref against the latest `origin/main`.

## Setup

You need [scala-cli](https://scala-cli.virtuslab.org/) on `PATH`. JVM 21 is auto-fetched by scala-cli if needed.

```bash
./scripts/mcp-server.scala
```

First launch downloads dependencies (~1 minute) and clones the upstream repo. Subsequent launches are seconds. The server listens on `http://localhost:8080/mcp`.

## Wiring it into Claude

For Claude Desktop / CLI clients that support MCP over HTTP, add an entry pointing at the server. The same JSON shape works both project-local (commit `.mcp.json` at the project root — picked up automatically by Claude Code) and user-global (`~/.config/claude/mcp.json` or your client's equivalent). Example:

```json
{
  "mcpServers": {
    "madrileno": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Restart your Claude client. Ask Claude something like *"call `madrileno_overview` and tell me what's available"* to verify the connection.

## A worked scenario

You're building a CRM for a car repair shop. You want an `Appointment` module: customer, vehicle, scheduled time, status.

A concrete prompt to paste into a fresh Claude session (the project's `.mcp.json` must already point at the running server — see above):

> I'm building **garage-crm** — a small CRM for a car repair shop — on top of the madrileno Scala 3 template.
>
> Add the first feature: an `Appointment` module. An appointment has:
>
> - a `customerId` (links to a `User`)
> - a `vehicleId` (opaque type, create it inline — no Vehicle aggregate yet)
> - a `scheduledAt` (Instant)
> - a `status` enum: `Scheduled`, `InProgress`, `Completed`, `Cancelled`
>
> Follow the template's existing module patterns. There's a `madrileno` MCP server connected — use it as your reference. Verify with `sbt compile` when done.

With the MCP wired up, Claude's flow looks like:

1. Calls `madrileno_overview()` — sees the reference modules (`user`, `auction`, `auth`, `healthcheck`), gets the doc index, learns about the scaffold script.
2. Calls `madrileno_doc("adding-a-module")` and `madrileno_doc("principles")` — the conventions (behavior-on-values, sealed-monad, single-aggregate-per-module).
3. Calls `madrileno_module("user")` — the canonical small aggregate (opaque `UserId`, validated opaque `EmailAddress`, repository + filter + soft-delete).
4. Calls `madrileno_module("auction")` — the richer reference (FKs via `ForeignIdTable`, `update[E]` callback with `SELECT … FOR UPDATE`, sealed-monad in the service, behavior methods on the aggregate, `text.asEnum` for status enums).
5. Runs `scripts/scaffold-module.scala Appointment appointments` → skeleton on disk.
6. Customises the domain (adds the status state machine + per-transition rejection ADTs + `Appointment.schedule` smart constructor), repo, service, router, DTO + matching specs, all using the patterns from step 3-4.

The MCP doesn't dictate the answer — it routes Claude to the parts of the reference that matter. What it generates is shaped by the reference, not by guesses.

## Pulling upstream changes

Months after init, upstream Madrileno has evolved. To learn what's new in, say, the auth layer:

```
> madrileno_changes(paths=["src/main/scala/madrileno/auth"])
840b2ac auth: provider map refactor + dev login
208deeb Config: type AppConfig.environment as Environment enum
...
```

Ask Claude to walk through those commits against your auth code and propose updates. After applying, bump `ref=` in `.madrileno-ref` to the new sha. That's the "stay in sync with upstream patterns" loop.

## Refreshing the shadow clone

`git fetch origin` runs on every server startup. If upstream landed something while the server's running and you want to pick it up without restarting, kill and restart the server. (A `madrileno_refresh()` admin tool may land later if this becomes annoying.)

## When not to use it

- Just reading a single doc once: open `https://github.com/luksow/madrileno/blob/<sha>/docs/<name>.md` in a browser. Faster than spinning up the server.
- The shadow clone is stale (no recent `git fetch` ran) — restart the server.
- Your project diverged so far from madrileno's patterns that the reference no longer maps. At that point the MCP isn't lying, but its suggestions are background noise.

## Known wrinkles

- **HTTP transport, not stdio.** You start the server manually before each Claude session. chimp's only transport today. Documented papercut.
- **chimp is at 0.1.x.** Early-stage MCP library; API may shift. `scripts/mcp-server.scala` will need version bumps occasionally.
- **First-launch is slow.** Java 21 download (if absent) + scala-cli compile + git clone. Subsequent launches are fast.
- **No auth.** The server listens on `localhost`. Don't bind it to public interfaces.

## Where to look next

- [`scripts.md`](scripts.md) — the rest of the scala-cli scripts under `scripts/` (init, scaffold, dev-console).
- [`adding-a-module.md`](adding-a-module.md) — the vertical-slice walkthrough the MCP's `madrileno_doc("adding-a-module")` returns.
