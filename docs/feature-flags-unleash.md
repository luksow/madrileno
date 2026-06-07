# Feature flags via Unleash — spike

This branch is an alternative to the roll-our-own approach in PR #79 / Phase 1. It demonstrates what feature flags look like when delegated to a self-hosted [Unleash](https://www.getunleash.io/) server via its official [Java SDK](https://docs.getunleash.io/reference/sdks/java).

**Status: spike for comparison.** The branch is intentionally minimal — same `FeatureFlagService` trait surface as PR #79 so the diff is concentrated in the implementation. No `Module` wiring, no `Main` hookup, no testcontainers integration tests.

## What it costs operationally

- **One more service** in `docker-compose.yml` — Unleash server + a dedicated Postgres for its own schema. Brings up at `http://localhost:54242` (admin login `admin` / `unleash4all`).
- **One more service** to operate in production — managed Unleash starts around $50/mo; self-hosting is another container + DB to back up, monitor, upgrade.
- **Mutable singleton SDK** — the Java client is constructed once and is the source of truth. We wrap it in a `Resource[IO, FeatureFlagService]` for lifecycle and use `IO.blocking` per evaluation (in-memory after the first poll, so it's cheap, but it is technically blocking).

## What we gain

- **No schema, no eval engine, no repo, no cache** in our code — Unleash owns all of it.
- **Web admin UI** out of the box at `localhost:54242`.
- **Mature targeting DSL** — segments, percentage rollouts with sticky bucketing, prerequisites, custom strategies, multi-environment promotion.
- **Frontend SDK / Proxy** — solves the "deliver flags to the browser" problem with first-class support.
- **Battle-tested** at scale.

## Trait → SDK mapping

The `FeatureFlagService` trait is OpenFeature-shaped — same surface as PR #79. The Unleash impl translates:

| Our API | Unleash SDK call | Notes |
|---|---|---|
| `evaluateBoolean(key, default)` | `unleash.isEnabled(key, ctx, default)` | Direct mapping. |
| `evaluateString(key, default)` | `unleash.getVariant(key, ctx).getPayload().getValue()` filtered to type=`string` | Returns default with `VariantTypeMismatch` if payload type doesn't match. |
| `evaluateInt(key, default)` | `unleash.getVariant(...)` with payload type=`number` and `String.toInt` | Same mismatch handling. |
| `evaluateJson(key, default)` | `unleash.getVariant(...)` with payload type=`json` and circe parse | Same. |

`EvaluationContext` maps to `UnleashContext` via `.userId(targetingKey)` + `.addProperty(k, v)` for each attribute.

**Impedance mismatch worth flagging:** Unleash splits flags into "boolean" (on/off) and "variant" (named weighted payloads, primarily designed for A/B testing). Our typed `evaluateString/Int/Json` calls all go through the variant API, which means non-boolean flags in Unleash are technically variant flags carrying a single variant with the value as payload. This works but is slightly off-label compared to LaunchDarkly-style "this flag's value is X" semantics.

## What's still missing for production adoption

| Item | Notes |
|---|---|
| **Module + Main wiring** | `FeatureFlagModule` extending `Application*Provider`s; `Main` allocates the `Resource[IO, FeatureFlagService]` once and threads through `ApplicationLoader`. Mirror the `EventBusRuntime` pattern. |
| **Testcontainers integration test** | `GenericContainer("unleashorg/unleash-server")` + a smoke test that registers a flag via the Unleash admin API, queries it via our trait, asserts the answer. Without this, the mapping is untested against real Unleash semantics. |
| **`/v1/feature-flags` bootstrap endpoint** | Unleash has its [Frontend API](https://docs.getunleash.io/reference/front-end-api) — could either expose ours that delegates, or point the frontend at Unleash directly. |
| **Audit log** | Unleash already audits in its own DB; if you want your audit log to live in YOUR DB too, you'd subscribe to Unleash's webhook events and persist. |

## Trade-off vs roll-our-own (PR #79)

| | This spike (Unleash) | PR #79 (roll-our-own) |
|---|---|---|
| **Code in madrileno** | ~150 LOC (one wrapper class) | ~560 LOC (schema + domain + engine + service + tests) |
| **Operational footprint** | +2 containers (Unleash + its PG) | 0 — uses existing Postgres |
| **Capability coverage** | 100% — Unleash is the system | 80–90% (no web UI, fewer operators, no multi-env workflow) |
| **In-tree teaching** | "Here's an SDK wrapper" | "Here's how a flag system is built" — exercises every madrileno layer |
| **Vendor lock** | Light — Unleash OSS is BSD-licensed and self-hostable; the Pro/SaaS tier is opt-in | None |
| **Effort to wire** | ~1–2 days (this PR is ~80% of it) | ~8–11 days for full Phases 1–4 |

## Recommendation

For madrileno's positioning as "self-contained, all-in-Scala-and-Postgres" backend template — **roll our own (PR #79).** The 20% capability gap (web UI, deepest targeting operators) doesn't matter at the scale typical of madrileno forks.

For a real app at scale where multi-environment promotion and a web UI matter more than self-containment — **adopt this spike, complete the missing pieces.** The `FeatureFlagService` trait is the same in both worlds, so business code is unchanged either way.
