# Frontend

Madrileno is a backend template, but the routes it exposes only earn their keep once something consumes them. The **reference frontend** — a separate repo, [`luksow/madrileno-frontend`](https://github.com/luksow/madrileno-frontend) — is that something: a React app built entirely against this backend's **generated contract**, so the Scala router specs stay the single source of truth and any drift is a compile error on the client.

It is deliberately a **sibling repo, not a subdirectory**, and the backend stays completely unaware of it — no file here depends on, imports, or names the frontend. The only coupling is the contract the backend already generates for its OpenAPI docs.

## The contract loop

Baklava turns the router specs — the same test-driven specs that produce the OpenAPI surface (see [`http.md`](http.md)) — into a typed TypeScript client package on `sbt test`. The frontend vendors that output and builds its API layer on top:

```
router specs ──sbt test──▶ target/baklava/orpc/src/*.ts     (oRPC contract + zod schemas + client)
                                    │  the frontend's `sync-contracts` copies them in
                                    ▼
                    madrileno-frontend/src/contracts/        (committed, so the app builds standalone)
                                    │  typed client + TanStack Query hooks
                                    ▼
                              tsc  ◀── a renamed backend DTO field is a compile error at the call site
```

Rename a field in a router spec, run `sbt test`, resync on the frontend, and its typecheck fails at the exact call site that read the old shape. That drift-as-compile-error is the whole point of the pairing — the wire boundary is checked by two compilers, not by hope.

The emitted package name and format are configured by the baklava generate config in [`build.sbt`](../build.sbt) (the `orpc-package-contract-json` block, published as `@madrileno-dev/<project>-orpc-contracts`). Nothing else here knows the frontend exists.

## What the frontend is

At a glance — details live in the frontend repo's own README:

- **React + Vite + TypeScript (strict)**, SPA by default with **SSR as a working opt-in** for the public browse pages (the SEO / first-paint set that needs no token at render time).
- **oRPC client** over the generated contract, with **typed errors end to end** — the backend's RFC 9457 problem envelope (see [`error-handling.md`](error-handling.md)) is decoded into discriminated errors the UI dispatches on by code, never by matching display text.
- **TanStack Query** for server state; **react-hook-form + zod** for forms; **Temporal** for time at the wire boundary (JS `Date` is confined to a single mapper module).
- Auth against this backend's dev login + JWT/refresh flow (see [`auth.md`](auth.md)), with a single-flight 401-refresh at the fetch layer.

## Why a separate repo (and not a subdirectory or a fullstack framework)

| Option | Verdict |
| ------ | ------- |
| **Separate sibling repo** (chosen) | The backend template stays a backend template — no Node toolchain, no frontend build in its CI, no coupling past the generated contract. Either repo can be adopted, replaced, or deleted without touching the other. |
| **Frontend subdirectory in this repo** | Skipped. Drags a Node/pnpm toolchain and a second CI surface into a Scala template, muddies [`init-project`](scripts.md), and forces one release cadence onto two very different stacks. |
| **Fullstack framework** (Next-style, shared types) | Skipped. Couples the two stacks at the hip and throws away the "backend is the source of truth, everything else consumes its documented contract" property the whole template is built around. |

## Using it

Clone it next to this repo, point its `sync-contracts` at `../madrileno/target/baklava/orpc/src`, and follow its README. The backend needs no changes:

- **In dev**, the frontend's Vite proxy makes the API same-origin, so there is no CORS to configure and nothing to change here.
- **In production**, if the frontend is served from a different origin, set this backend's `CORS_ALLOWED_ORIGINS` (see [`configuration.md`](configuration.md)) — its existing env contract, not a new frontend coupling.
