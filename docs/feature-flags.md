# Feature flags

A production-grade flag system living under `utils/featureflag`: typed variants, targeting rules, reusable segments, percentage rollouts, an admin API with an audit trail, and a client bootstrap endpoint. It is a deletable battery — nothing in the rest of the app requires it until you reach for it.

The domain is deliberately tight and well-typed; evaluation is OpenFeature-shaped (a value plus a *reason*), and reads never throw — a missing flag, a type mismatch, or a backend hiccup all fall back to the caller's default.

## Domain model

A `FeatureFlag` is a key, a typed default, an `enabled` switch, a `clientExposed` flag, and an ordered list of rules:

```scala
final case class FeatureFlag(
  id: FlagId, key: FlagKey, description: FlagDescription,
  enabled: Boolean, defaultValue: FlagVariant, clientExposed: Boolean,
  rules: List[Rule], createdAt: Instant, updatedAt: Instant)

enum FlagVariant:           // the value a flag resolves to; the type is fixed per flag
  case BoolVariant(value: Boolean)
  case StringVariant(value: String)
  case IntVariant(value: Int)
  case JsonVariant(value: Json)
```

The vocabulary, smallest to largest:

- **Variant** (`FlagVariant`) — the value a flag resolves to, in one of four types: boolean, string, int, or JSON. A flag's type is fixed: its default and every rule outcome must produce the same variant type.
- **Condition** (`RuleCondition`) — a single predicate evaluated against the context. Either an attribute comparison (`StringEquals/In/Contains/StartsWith`, `IntEquals/GreaterThan/LessThan`, `BoolEquals`) or `SegmentMatch(name)`.
- **Segment** — a named, reusable list of conditions (e.g. `premium-wines`, `verified-users`). A segment matches when *all* its conditions hold. Rules reference it by name via `SegmentMatch`, so one audience definition is maintained in one place and reused across many flags.
- **Outcome** (`RuleOutcome`) — what a matched rule yields: a `FixedValue(variant)`, or a `PercentageRollout(percentage, seed, onMatch)` that hashes the targeting key into a bucket and returns `onMatch` for the chosen fraction (and the flag's default for everyone else).
- **Rule** — a `(conditions, outcome)` pair with a `position`. A flag's rules are evaluated in `position` order and the first whose conditions *all* match wins; its conditions are AND-ed.
- **Feature flag** (`FeatureFlag`) — the aggregate above: a typed `defaultValue`, an `enabled` master switch, a `clientExposed` flag (does it ride the client bootstrap?), and the ordered `rules`. Evaluation returns the first matching rule's outcome; if the flag is disabled or no rule matches, the `defaultValue`.

Two more types describe a single evaluation rather than the flag's configuration:

- **Evaluation context** (`EvaluationContext`) — the input: a `targetingKey` and `attributes` (see [below](#targeting-key-vs-attributes)).
- **Evaluation detail** (`EvaluationDetail`) — the output: the resolved value plus a `reason` (and, on failure, an `errorCode`). The plain `evaluateX` calls hand back just the value; `evaluateXDetail` returns the whole record.

Every variant-type mismatch is caught at write time: a rule whose outcome variant doesn't match the flag's default type is rejected (`400`), as are duplicate rule positions.

## Evaluating a flag

Inject `FeatureFlagService` and use the typed API. Each call takes a caller default, returned when the flag is missing, the call's type doesn't match the flag's variant, or evaluation errors. (A *disabled* flag is different: it returns the flag's own configured `defaultValue` — see the kill-switch note below.)

```scala
val ctx = EvaluationContext(TargetingKey(user.id.toString))
featureFlagService.evaluateBoolean(FlagKey("new-checkout"), ctx, default = false)
// or the detail form, when you want the reason:
featureFlagService.evaluateBooleanDetail(FlagKey("new-checkout"), ctx, default = false)
//   => EvaluationDetail(value, reason, errorCode, errorMessage)
```

`evaluateString`, `evaluateInt`, `evaluateJson` (and their `…Detail` siblings) round out the surface. `reason` is one of `Disabled`, `TargetingMatch`, `Split` (a percentage rollout decided the value), `Default`, or `Error`; on `Error`, `errorCode` is `FlagNotFound`, `TypeMismatch`, or `General`.

### Targeting key vs. attributes

`EvaluationContext` has two parts that do different jobs — keep them straight:

- **`targetingKey`** — a *stable identity*. It is the only input to percentage bucketing: a `PercentageRollout` hashes `seed + ":" + targetingKey` (MurmurHash3) into `[0,100)`, so a given key lands in the same bucket every time and a flag ramps monotonically as the percentage rises. Use the user id; compose it (`s"$userId:$tenantId"`) only when you want independent bucketing per resource.
- **`attributes`** — the *facts rules and segments match on* (`plan`, `region`, `email-verified`, …). An empty attribute map means segments and attribute conditions have nothing to match, so they never fire. This is where "more than just an id" lives.

```scala
EvaluationContext(
  TargetingKey(user.id.toString),
  Map(AttributeName("plan") -> AttributeValue("enterprise"))
)
```

## Caching and invalidation

Evaluation is fronted by three per-JVM caches (60s TTL): per-key flags, the segment set, and the client-exposed flag list. Writes invalidate synchronously on the instance that made the change and publish a `FeatureFlagEvent` on the [event bus](event-bus.md); peers pick it up and invalidate their own caches (falling back to the TTL if a publish is lost). Cache writes are epoch-guarded so a slow load can't resurrect a value that was invalidated mid-flight. See [cache.md](cache.md) for the runtime.

## Admin API

Behind the basic-auth [admin gate](http.md), under `/admin`:

| Method & path | Purpose |
| --- | --- |
| `GET/POST /admin/feature-flags` | list / create (409 on key conflict, 400 on validation) |
| `GET/PUT/DELETE /admin/feature-flags/{key}` | fetch / replace / delete (id, key, createdAt are immutable on update) |
| `POST /admin/feature-flags/{key}/toggle` | enable/disable without touching rules |
| `GET /admin/feature-flags/{key}/audit` | paginated audit trail |
| `POST /admin/feature-flags/{key}/evaluate` | dry-run against an arbitrary context, bypassing caches |
| `GET/POST /admin/feature-flag-segments`, `PUT/DELETE …/{name}` | segment CRUD |

Every flag mutation is written in one transaction with its audit entry — actor (the authenticated admin), action, and full before/after snapshots. Deleting a flag detaches its audit rows (`ON DELETE SET NULL`) rather than dropping them, so the trail survives and stays queryable by key.

## Client bootstrap

`GET /v1/feature-flags` (authenticated) evaluates every `clientExposed` flag for the current user and returns a `{ key: value }` map — the frontend fetches it once at startup. The evaluation context keys on the user id, and every scalar field of the `AuthContext` is flattened into an attribute (so `emailVerified` is available to segments out of the box); add a field to `AuthContext` and it shows up automatically.

## Worked example: the auction module

Two flags wire feature flagging into the auction domain. Both ship **inert** — until you create them via the admin API they don't exist, so evaluation falls back to the caller default and behaviour is unchanged. They are not seeded.

**`auction.min-bid-increment-pct`** (`IntVariant`, default `0`) — resolved on every bid in `AuctionService.placeBid` and passed into the pure `Auction.placeBid`, which raises the minimum acceptable bid to `floor × (100 + pct) / 100`. The context carries the *auction's* attributes (`wine-region`, `wine-color`, `wine-vintage`, `bottle-size`), so a `premium-wines` segment can demand a larger jump on high-end lots — a demonstration that context isn't always about *who* is asking.

```bash
# require a 5% increment on Bordeaux lots
curl -u admin:admin -X POST localhost:9000/admin/feature-flag-segments -H 'content-type: application/json' -d '{
  "name": "premium-wines", "description": "high-end lots",
  "conditions": [{"StringEquals": {"attribute": "wine-region", "value": "Bordeaux"}}]
}'
curl -u admin:admin -X POST localhost:9000/admin/feature-flags -H 'content-type: application/json' -d '{
  "key": "auction.min-bid-increment-pct", "description": "min bid increment %",
  "enabled": true, "defaultValue": {"IntVariant": {"value": 0}}, "clientExposed": false,
  "rules": [{"position": 0, "description": "premium lots step 5%",
    "conditions": [{"SegmentMatch": {"name": "premium-wines"}}],
    "outcome": {"FixedValue": {"value": {"IntVariant": {"value": 5}}}}}]
}'
```

**`auction.show-wine-ratings`** (`BoolVariant`, `clientExposed`) — gates the Vivino [gateway](external-apis.md) call in `getAuction`, which evaluates the flag with a caller default of `true`, so ratings show out of the box and you create the flag to turn them *off*. The flag is client-exposed, so it also rides the bootstrap payload for the frontend's ratings widget. `getAuction` is unauthenticated, so it evaluates with an anonymous context — a reminder that not every flag is personalised.

> **Turning a flag "off" means evaluating to `false`, not disabling it.** Set the flag's value to `false` (e.g. `defaultValue` or a rule). The `enabled` switch is *not* a kill switch: a disabled flag returns its configured `defaultValue`, so disabling `auction.show-wine-ratings` while its default is `true` leaves ratings on. This holds for every flag — `enabled = false` means "stop evaluating rules, fall back to the default", which is only "off" when the default itself is off.

## Adding your own flag

1. Pick a key (`[a-z][a-z0-9_-]*(\.[a-z0-9_-]+)*` — lowercase, with `.` for hierarchy, e.g. `billing.new-dunning`).
2. Evaluate it where the decision is made, with a sensible caller default, building the context from whatever the surrounding code already holds.
3. Create it through the admin API (or leave it absent to keep the default). Mark it `clientExposed` only if the frontend needs it.

See [testing-guide.md](testing-guide.md) for how the showcase flags are exercised — `FeatureFlagServiceSpec`, `AuctionServiceSpec`, and the bootstrap targeting test in `FeatureFlagRouterSpec`.
