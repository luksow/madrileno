# API versioning

Status: **one version, `v1`.** Every HTTP endpoint mounts under `/v1/*`. This document describes how to add `/v2/*` when (if) that day comes.

## Mechanism

**Path prefix**, declared as a Scala 3 enum at `madrileno.utils.http.ApiVersion`:

```scala
enum ApiVersion(val urlSegment: String) {
  case V1 extends ApiVersion("v1")
}
```

`ApplicationLoader.routes` mounts the module route tree under every value in `ApiVersion.values`. Today that's just `v1`; when `V2` is added to the enum, the matcher automatically also mounts under `/v2/*` — no wiring change.

## Why path-prefix (and not the alternatives)

| Option | Verdict |
| ------ | ------- |
| **Path prefix** (`/v1/users/{id}`) | **Chosen.** Trivial to route, debug, cache, generate OpenAPI specs against. Works in browser dev-tools, Postman, curl, every proxy and CDN without special configuration. |
| **Content negotiation** (`Accept: application/vnd.madrileno.v2+json`) | Skipped. "Pure REST" but practical friction is real: curl users / Postman / OpenAPI generators all need to learn the header dance, and some HTTP infrastructure mishandles Accept-based negotiation. The "per-endpoint versioning" purity gain doesn't pay for the developer-experience cost. |
| **Custom header** (`X-API-Version: 2`) | Skipped. Same friction as Accept-based, without the standard-MIME-type credibility. |
| **Query parameter** (`?version=2`) | Skipped. Mixes API-version with query semantics; visually noisy in URLs; doesn't combine well with the rest of the query string for paginated/filtered endpoints. |
| **Date pinning (Stripe-style)** | Skipped. Massive operational complexity — you maintain N date-pinned shapes in parallel with a transform layer. Overkill for the indie-hacker audience madrileno targets. |

## Why no `app.api-version` in HOCON

The version is determined by the code's shape, not by environment. If your code mounts routes that fit a `v1` contract, then the prefix is `v1`. There is no scenario where an env override should re-route the entire API to a different prefix — that's either a bug in the deploy config or the wrong tool. Keeping it out of HOCON also prevents accidental drift between what `application.conf` says and what the routes actually expose.

The enum is the single source of truth.

## Two patterns

### Pattern B (prefer this) — localized changes

**~80% of real-world "version" needs are one endpoint changing.** The right move is almost always:

- **Additive change**: add a new optional field to the response, or a new optional field to the request. Old clients ignore the new field; new clients use it. No version bump.
- **New endpoint**: when the shape genuinely changes, add `POST /v1/users/{id}/profile` or `POST /v1/users/{id}/preferences` alongside the existing endpoint. Keep the URL stable as long as the change is backward-compatible.
- **New parameter**: optional query/body parameters that gate the new behaviour (`GET /v1/users/{id}?include=profile`).

Do not bump the global version for a localized change. That's the cardinal sin of versioning — touching one endpoint shouldn't churn URLs for every other endpoint.

### Pattern A — big-bang bump

When a coordinated breaking change spans many endpoints (rare):

1. **Add `V2` to the `ApiVersion` enum:**

   ```scala
   enum ApiVersion(val urlSegment: String) {
     case V1 extends ApiVersion("v1")
     case V2 extends ApiVersion("v2")
   }
   ```

   `ApplicationLoader.routes` now mounts every module's routes under both `/v1/*` and `/v2/*` — without any wiring change.

2. **Differentiate per version where the shape changes.** This is the work madrileno hasn't yet built — when you need it, the cleanest pattern is to thread the matched `ApiVersion` into routes (via a `Directive1[ApiVersion]` from `apiPrefix`, or via an `IOLocal[ApiVersion]` populated at the prefix-matching site) and branch inside the affected route. Modules whose endpoints didn't change need no per-version code.

3. **Migrate clients off `/v1`.**

4. **Deprecate `/v1`** by emitting `Deprecation: true` + `Sunset: <date>` response headers (RFC 8594) on `/v1/*` responses. A `deprecated(sunset: Instant)` stir directive is the natural place for this — also not yet built; add when first deprecating.

5. **Remove `V1` from the enum** after the sunset date passes and you've verified no client traffic remains.

The work in steps 2 and 4 is intentionally deferred. Building it speculatively against a hypothetical future shape risks getting the abstraction wrong. The enum-based mounting is the structural foundation; the per-version branching pattern can be designed when there's a real second version to motivate it.

## What's NOT here

- **Per-endpoint version branching directive** — deferred until v2 exists
- **`deprecated(sunset)` directive emitting RFC 8594 headers** — deferred until first deprecation
- **Multi-version OpenAPI generation** — Baklava emits one spec per `paths` tree today; v2's spec would need to either coexist or supersede. Not yet investigated.
