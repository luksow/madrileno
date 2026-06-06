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

2. **Differentiate per version where the shape changes.** Swap `ApplicationLoader.apiPrefix` for `ApiVersionDirectives.apiVersionPrefix` — drop-in `Directive0` replacement that stores the matched version on the request attributes. Inner routes look it up on demand; no callback parameter to thread:

   ```scala
   apiVersionPrefix {
     path("users") {
       apiVersion(ApiVersion.V1)(complete(v1Shape)) ~
       apiVersion(ApiVersion.V2)(complete(v2Shape))
     }
   }
   ```

   For handlers that need the value inline (e.g. one endpoint returning different DTO shapes per version), use `currentApiVersion`:

   ```scala
   apiVersionPrefix {
     path("users" / JavaUUID) { id =>
       currentApiVersion { v =>
         complete(userService.find(id).map(_.toDto(v)))
       }
     }
   }
   ```

   Modules whose endpoints didn't change need no per-version code — they register routes once and run under both prefixes.

3. **Migrate clients off `/v1`.**

4. **Deprecate `/v1`** by wrapping `/v1/*` routes with `ApiVersionDirectives.deprecated(sunsetDate)`. The directive adds `Deprecation: true` (RFC 9745) and `Sunset: <http-date>` (RFC 8594) response headers, so well-behaved clients see the warning ahead of the sunset.

5. **Remove `V1` from the enum** after the sunset date passes and you've verified no client traffic remains.

`ApiVersionDirectives` lives at `madrileno.utils.http.ApiVersionDirectives` — three building blocks, tested in `ApiVersionDirectivesSpec`, currently unused. The wiring change in step 2 (swap `apiPrefix` for `apiVersionPrefix`) and the call-site additions in steps 2 and 4 are the only changes needed at that point.

## What's NOT here

- **`ApplicationLoader` is still wired to the `Directive0` form of the prefix.** When V2 is added to the enum, swap the loader's `apiPrefix` for `ApiVersionDirectives.apiVersionPrefix` so inner routes can branch. The directives themselves are built and tested already; the only thing missing is the call site change.
- **Multi-version OpenAPI generation** — Baklava emits one spec per `paths` tree today; V2's spec would need to either coexist or supersede. Not yet investigated.
