# Authentication

The auth model is a thin layer of conventions on top of OAuth-flavoured external identity providers. Firebase, any number of generic OIDC OPs (Zitadel, Auth0, Keycloak, …), and a dev-only email-shortcut provider all sit behind a single `ExternalAuthVerifier` trait, multiplexed by an `AuthVerifiers` map keyed by `Provider`. The `Authenticated` JWT and refresh-token plumbing downstream is provider-agnostic.

The flow:

```
                               ┌───────────────┐
client logs in with Firebase   │   Firebase    │
─────────────────────────────► │  client SDK   │
                               └───────┬───────┘
                                       │ Firebase JWT
                                       ▼
client → POST /v1/auth/firebase {firebaseJwtToken}
                                       │
                                       │ verify Firebase JWT
                                       ▼
                          ┌──────────────────────────┐
                          │ ExternalAuthVerifier     │
                          │   = FirebaseService      │  → returns
                          └──────────────────────────┘    VerifiedExternalToken
                                       │
                                       ▼
                          ┌──────────────────────────┐
                          │ AuthenticationService    │  upserts User,
                          │                          │  records UserAuth,
                          │                          │  mints internal JWT,
                          │                          │  issues RefreshToken
                          └──────────────────────────┘
                                       │
                                       ▼
client ◄─── 200 {jwt, refreshTokenId}

later requests:
client → Authorization: Bearer <internal jwt>
                                       │
                                       ▼
                          ┌──────────────────────────┐
                          │ UserAuthenticator        │
                          │   decodes the JWT,       │
                          │   produces AuthContext   │
                          └──────────────────────────┘
                                       │
                                       ▼
                          AuthRouteProvider.route(auth)
```

External identity is verified once; from then on the app trusts its own short-lived JWT and a longer-lived refresh token.

## The pieces

| File                              | What it does                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `ExternalAuthVerifier`            | The trait. One method: `verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]]`.            |
| `AuthVerifiers`                   | `Map[Provider, ExternalAuthVerifier]`. Built in `AuthModule` from config; `authenticateWithProvider` dispatches through it. |
| `Rs256TokenVerifier`              | Shared RS256-JWT core. Verifies signature via a `kid → RSAPublicKey` resolver, then `iss` / `aud` / `exp` / `nbf` with 30 s leeway. |
| `FirebaseService`                 | Wraps `Rs256TokenVerifier` for Firebase: issuer derived from `FIREBASE_PROJECT_ID`, keys fetched via `FirebaseKeyProvider` (Google's x509 endpoint). Enabled when the project id is set. |
| `JwksProvider` + `OidcDiscovery`  | Used by the generic OIDC path: fetch a JWKS (cached, key-rotation-aware) and discover `jwks_uri` from `<issuer>/.well-known/openid-configuration` if not configured. |
| `DevAuthVerifier`                 | Treats an email-shaped token as a verified identity; reached via `POST /v1/auth/dev`. Registered only when `dev-auth.enabled = true` (`DEV_AUTH_ENABLED`); the route is always wired but returns 404 `unknown-provider` when the verifier isn't registered. |
| `JwtService`                      | Encodes / decodes the app's own JWT (`InternalJwt`). HS256 (`com.auth0:java-jwt`). Signs an `AuthContext`.    |
| `AuthenticationService`           | `authenticateWithProvider(provider, cmd)`: verify external → upsert `User` + `UserAuth` → mint internal JWT + refresh token. |
| `AuthRouter`                      | `POST /v1/auth/firebase`, `POST /v1/auth/oidc/{provider}`, `POST /v1/auth/dev` (dev only), `POST /v1/auth/refresh-token`, `GET/DELETE /v1/auth/sessions`. |
| `UserAuthenticator`               | The function passed to `authenticateOrRejectWithChallenge`. Decodes the internal JWT, returns `AuthContext`.  |
| `RefreshTokenRepository`          | Persists refresh tokens; supports listing by user, revocation by id or user-agent.                            |
| `cleanupExpiredRefreshTokensTask` | Recurring task that deletes used/revoked rows older than 60 days (tombstone GC).                              |

## `AuthContext`

The data the rest of the app sees once a request is authenticated:

```scala
final case class AuthContext(
  userId: UserId,
  fullName: Option[FullName],
  avatarUrl: Option[URI]
)
```

It's the JWT payload, minted at login and decoded on every authenticated request. The default fields are deliberately small — just enough for routes to know who's calling without another DB lookup.

Add fields when your routes consistently need them. Tenant id, role, account-tier, feature-flag bundle — anything you'd otherwise have to look up on every request — fits well here. Update the case class, the `AuthenticationService` mint sites, and `AuthContext.given Encoder/Decoder` (kebs derives them). Existing routes need no changes; they just have more on the `auth` parameter.

Two caveats. First, every field added grows every JWT — fine for stable identity-shaped data, wasteful for things that churn (last-seen-at, online-status). Second, the JWT is signed but not encrypted; don't put anything there you wouldn't be comfortable putting in a base64-encoded string the client can decode. For data that changes often or shouldn't be visible to the client, do a per-request DB lookup instead.

`AuthRouteProvider.route(auth: AuthContext)` is how a module receives the authenticated context. From inside a router:

```scala
def authedRoutes(authContext: AuthContext): Route = {
  (post & path("auctions") & entity(as[CreateAuctionRequest])) { request =>
    complete {
      auctionService.createAuction(authContext.userId, request)…
    }
  }
}
```

## The login flow

1. **Client obtains a Firebase ID token** using a Firebase client SDK (browser, mobile, whatever Firebase supports). The app doesn't see passwords or social-login flows — those are Firebase's job.

2. **Client `POST /v1/auth/firebase`** with the Firebase JWT. The route extracts the user-agent and IP, then calls `AuthenticationService.authenticateWithProvider(Provider.Firebase, …)`.

3. **Server verifies the Firebase JWT** through `ExternalAuthVerifier.verifyToken`. `FirebaseService` validates the RS256 signature against Google's published certificates (fetched once, cached, re-fetched on key rotation), checks `iss` / `aud` / `exp`, and returns a `VerifiedExternalToken` carrying the Firebase user-id, email, name, and photo URL.

4. **Server upserts the User and UserAuth.** `User` is the application's user record; `UserAuth` records the link to a Firebase identity. First-time logins create both; returning users update them with whatever Firebase reported (e.g. updated avatar).

5. **Server mints the internal JWT and a refresh token.** The JWT signs an `AuthContext` with `jwt.secret`, valid for `jwt.valid-for` (default 5 minutes). The refresh token is a row in `refresh_token` keyed by a UUID; the client gets back the row's UUID, the server keeps the rest (user-agent, IP, created-at, used-at).

6. **Result:** `200 { jwt, refreshToken, userCreated }`. `userCreated` is `true` when this call provisioned a new `User` account (first-time login), `false` for a returning user — clients use it to branch their UX (show onboarding vs. just log in). The status code is `200` either way; the "was a user created" signal lives in the body so a typed client (ts-rest, OpenAPI codegen) sees one response shape, not two keyed on status. Subsequent requests carry `Authorization: Bearer <jwt>`.

## Refreshing

The internal JWT is short-lived. When it expires, the client `POST /v1/auth/refresh-token` with the refresh-token UUID. `AuthenticationService.authenticateWithRefreshToken` looks up the row, verifies it hasn't been used or revoked, marks it `used`, and issues a fresh JWT + a fresh refresh token.

Refresh tokens are one-time-use — using one invalidates it. This means a stolen refresh token is only useful until the legitimate client refreshes again, at which point the legitimate client's refresh fails and the user has to log in. There's no time-based expiry on a refresh token today; only one-time-use plus revocation. Adding an `expires_at` column and a check in `RefreshToken.isValid` is the natural place to evolve if you want time bounds.

`cleanupExpiredRefreshTokensTask` runs daily at 1 AM to delete rows that have been used or revoked for more than 60 days (tombstone garbage collection — not active-token expiration).

## OIDC providers

Any OpenID-Connect provider (Zitadel, Auth0, Keycloak, …) plugs in via the generic `Rs256TokenVerifier` + JWKS path — no provider-specific code needed, just a config entry. The frontend completes Authorization Code + PKCE against the provider in the browser; the backend verifies the resulting `id_token` and runs the same upsert + JWT-mint flow as Firebase.

Two ways to configure:

**HOCON map**, for multiple providers — edit `application.conf` (or an overlay):

```hocon
oidc {
  providers {
    zitadel {
      issuer   = "https://my-instance.zitadel.cloud"
      audience = "my-zitadel-app-id"
      # jwks-uri is optional; if omitted, it's discovered via
      # <issuer>/.well-known/openid-configuration
    }
    auth0 {
      issuer   = "https://my-tenant.auth0.com/"
      audience = "my-auth0-client-id"
    }
  }
}
```

Each entry becomes `POST /v1/auth/oidc/<name>` (`/v1/auth/oidc/zitadel`, `/v1/auth/oidc/auth0`).

**Env single slot**, for the common single-provider case:

```
OIDC_PROVIDER_NAME=oidc       # path segment, default "oidc"
OIDC_ISSUER=https://...
OIDC_AUDIENCE=client-id
OIDC_JWKS_URI=                # optional override; otherwise discovered
```

(When the env slot's name collides with a HOCON `oidc.providers` entry, the env slot wins.)

The verifier checks the token's signature against the provider's JWKS (refreshed hourly; on an unknown `kid` it re-fetches transparently to handle key rotation), validates `iss` matches the configured issuer, requires the token's `aud` to intersect the configured audience set, and accepts ±30 s clock skew on `exp` / `nbf` / `iat`. `audience` is comma-split, so `OIDC_AUDIENCE=client1,client2` accepts tokens with either in `aud`.

Same-email users from different providers are **separate** accounts — `UserAuth` is keyed by `(provider, providerUserId)`. Linking-by-verified-email is a separate, security-sensitive feature, not done out of the box.

## Dev login

`POST /v1/auth/dev { "email": "alice@example.com" }` synthesises a verified token from the email and logs you in (creating the user on first call, with `emailVerified = true` and `provider = "Dev"`). Gated by `dev-auth.enabled` (`DEV_AUTH_ENABLED`) — when the flag is off the route still exists but returns 404 `unknown-provider`. Default `.env.sample` ships `DEV_AUTH_ENABLED=true` so the stock checkout has a working login without any external provider configured; flip to `false` (or unset) for any deploy where dev login shouldn't be reachable.

## The auth gate

`ApplicationLoader.routes(wsb)` wraps everything under `/v1` in `authenticateOrRejectWithChallenge(userAuthenticator)`:

```scala
rawPathPrefix(Slash ~ apiVersion) {
  authenticateOrRejectWithChallenge(userAuthenticator) { auth =>
    route(auth) ~ wsRoutes(auth, wsb)        // authed routes
  } ~
  (route ~ wsRoutes(wsb))                    // public routes (auth optional)
}
```

`UserAuthenticator` decodes the `Authorization: Bearer …` header. Success → `Right(AuthContext)`, failure → a `WWW-Authenticate: Bearer realm="madrileno"` challenge with status 401.

A route inside the authenticated branch accepts the `AuthContext` parameter; a route outside it doesn't. `RouteProvider` (no auth) and `AuthRouteProvider` (auth required) are the two flavours; modules pick which one each route belongs to.

## Swapping the provider

Three points control where identity comes from:

- **`ExternalAuthVerifier` trait.** One method: `verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]]`. Implement it against any provider that maps a token to "this is who, with these attributes."
- **`AuthModule.externalAuthVerifiers: AuthVerifiers`** — a `Map[Provider, ExternalAuthVerifier]` (a `protected lazy val`). Override in a sub-trait or concrete `ApplicationLoader`; tests do this with `FakeAuthVerifier` (see `TestApplicationLoader`).
- **For OIDC OPs**, you don't write code — add an entry to `oidc.providers` (or set the `OIDC_*` env slot). `POST /v1/auth/oidc/<name>` is generic.
- **For non-OIDC providers**, implement `ExternalAuthVerifier`, wire it into the verifier map under a new `Provider("name")` constant, and add a dedicated route (mirroring `/v1/auth/firebase` or `/v1/auth/dev`). The downstream upsert / JWT-mint / refresh-token machinery is provider-agnostic via `AuthenticationService.authenticateWithProvider`.

For browser-based OIDC (Authorization Code + PKCE), this template assumes the frontend does the redirect handshake; the backend only verifies the resulting `id_token`. If you need a backend-driven flow (the server owns `/start` and `/callback`), that's an extra pair of endpoints around `Rs256TokenVerifier` — same downstream from there.

## What you can't do

- **Change the internal JWT's signing scheme to RS256/asymmetric.** `JwtService` uses HMAC. Easy to extend — provide a key pair and switch the algorithm — but not done out of the box.
- **Have multiple internal-JWT secrets / rotate them.** Single static secret. For rotation you'd add key-id support and a small key resolver in `JwtService`.
- **Federate identity across multiple providers per user.** `UserAuth` is one row per `(provider, providerUserId)`, and `User` has one canonical id. To allow "same user signs in via Google AND Apple," you'd link multiple `UserAuth` rows to one `User`.

None of these are deep changes; they're just not pre-built.

## Testing

`FakeAuthVerifier` returns a fixed `VerifiedExternalToken` for any input *except* its `invalidTokenValue` (default `"invalid-token"`), which it rejects — that's what lets a route spec assert the 401 path without setting up real signature verification. `TestApplicationLoader` wires it under both `Provider.Firebase` and `Provider("test-oidc")`, so route specs can `POST /v1/auth/firebase` or `POST /v1/auth/oidc/test-oidc` with any other string and get a predictable user back.

`Rs256TokenVerifierSpec` covers the RS256 verification core directly — an in-test RSA keypair signs tokens via `java-jwt`, and the verifier is exercised with a manual key resolver (happy path, wrong issuer / audience, foreign signing key, expired, missing kid, etc.).

`TestData.authContext()` builds an `AuthContext` for service-level tests that don't go through the auth flow at all.

For an end-to-end test that exercises the JWT path: log in via the test loader, capture the returned JWT, send it as `Authorization: Bearer …` on the next request. The same `userAuthenticator` decodes it.

## Where to look next

- [http.md](http.md) — `AuthRouteProvider`, the gate, and how `AuthContext` flows into `route(auth)`.
- [scheduler.md](scheduler.md) — `cleanupExpiredRefreshTokensTask` is a recurring task; the same pattern applies to anything you need on a schedule.
- [error-handling.md](error-handling.md) — `AuthenticationResult` is one of the canonical Result-ADT examples.
