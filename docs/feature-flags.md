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

Rules are evaluated in `position` order; the first whose conditions all match wins. A `RuleOutcome` is either a `FixedValue(FlagVariant)` or a `PercentageRollout(percentage, seed, onMatch)`. Conditions match against the evaluation context — `StringEquals/In/Contains/StartsWith`, `IntEquals/GreaterThan/LessThan`, `BoolEquals`, and `SegmentMatch(name)`. A `Segment` is a named, reusable condition list (e.g. `premium-wines`) referenced from rules via `SegmentMatch`, so the same audience can drive many flags.

Every variant-type mismatch is caught at write time: a rule whose outcome variant doesn't match the flag's default type is rejected (`400`), as are duplicate rule positions.

## Evaluating a flag

Inject `FeatureFlagService` and use the typed API. Each call takes a caller default that is returned on disabled / not-found / mismatch:

```scala
given ctx: EvaluationContext = EvaluationContext.of(TargetingKey(user.id.toString))
featureFlagService.evaluateBoolean(FlagKey("new-checkout"), default = false)
// or the detail form, when you want the reason:
featureFlagService.evaluateBooleanDetail(FlagKey("new-checkout"), default = false)
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

`GET /v1/feature-flags` (authenticated) evaluates every `clientExposed` flag for the current user and returns a `{ key: value }` map — the frontend fetches it once at startup. The evaluation context is built by an injected resolver so the module stays domain-agnostic; the default (`FeatureFlagModule.featureFlagContext`) keys on the user id and carries `email-verified` as an attribute. Override it to expose whatever your segments target on.

## Worked example: the auction module

Two flags wire feature flagging into the auction domain. Both ship **inert** — until you create them via the admin API they don't exist, so evaluation returns the caller default and behaviour is unchanged. They are not seeded.

**`auction-min-bid-increment-pct`** (`IntVariant`, default `0`) — resolved on every bid in `AuctionService.placeBid` and passed into the pure `Auction.placeBid`, which raises the minimum acceptable bid to `floor × (100 + pct) / 100`. The context carries the *auction's* attributes (`wine-region`, `wine-color`, `wine-vintage`, `bottle-size`), so a `premium-wines` segment can demand a larger jump on high-end lots — a demonstration that context isn't always about *who* is asking.

```bash
# require a 5% increment on Bordeaux lots
curl -u admin:admin -X POST localhost:9000/admin/feature-flag-segments -H 'content-type: application/json' -d '{
  "name": "premium-wines", "description": "high-end lots",
  "conditions": [{"StringEquals": {"attribute": "wine-region", "value": "Bordeaux"}}]
}'
curl -u admin:admin -X POST localhost:9000/admin/feature-flags -H 'content-type: application/json' -d '{
  "key": "auction-min-bid-increment-pct", "description": "min bid increment %",
  "enabled": true, "defaultValue": {"IntVariant": {"value": 0}}, "clientExposed": false,
  "rules": [{"position": 0, "description": "premium lots step 5%",
    "conditions": [{"SegmentMatch": {"name": "premium-wines"}}],
    "outcome": {"FixedValue": {"value": {"IntVariant": {"value": 5}}}}}]
}'
```

**`auction-show-wine-ratings`** (`BoolVariant`, default `true`, `clientExposed`) — a global kill-switch: `getAuction` skips the Vivino [gateway](external-apis.md) call when it is off, and because the flag is client-exposed it also rides the bootstrap payload so the frontend can hide the ratings widget in step. `getAuction` is unauthenticated, so it evaluates with an anonymous context — a reminder that not every flag is personalised.

## Adding your own flag

1. Pick a key (`[a-z][a-z0-9_-]*` — hyphen-namespaced, e.g. `billing-new-dunning`).
2. Evaluate it where the decision is made, with a sensible caller default, building the context from whatever the surrounding code already holds.
3. Create it through the admin API (or leave it absent to keep the default). Mark it `clientExposed` only if the frontend needs it.

See [testing-guide.md](testing-guide.md) for how the showcase flags are exercised — `FeatureFlagServiceSpec`, `AuctionServiceSpec`, and the bootstrap targeting test in `FeatureFlagRouterSpec`.
