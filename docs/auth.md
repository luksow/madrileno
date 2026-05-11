# Authentication

The auth model is a thin layer of conventions on top of OAuth-flavoured external identity providers. Firebase is the bundled provider, but everything that's specific to it sits behind a single `ExternalAuthVerifier` trait — swap the implementation, keep the rest.

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
| `FirebaseService`                 | Default implementation. Calls Firebase's Admin SDK to verify a Firebase ID token.                             |
| `JwtService`                      | Encodes / decodes the app's own JWT (`InternalJwt`). Symmetric HMAC. Signs an `AuthContext`.                  |
| `AuthenticationService`           | Orchestrates: verify external → upsert `User` + `UserAuth` → mint internal JWT + refresh token.               |
| `AuthRouter`                      | `POST /auth/firebase`, `POST /auth/refresh-token`, `GET/DELETE /auth/sessions`.                                |
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

2. **Client `POST /v1/auth/firebase`** with the Firebase JWT. The route extracts the user-agent and IP, then calls `AuthenticationService.authenticateWithFirebase`.

3. **Server verifies the Firebase JWT** through `ExternalAuthVerifier.verifyToken`. The `FirebaseService` implementation calls Firebase's Admin SDK, which returns a `VerifiedExternalToken` carrying the Firebase user-id, email, name, and photo URL.

4. **Server upserts the User and UserAuth.** `User` is the application's user record; `UserAuth` records the link to a Firebase identity. First-time logins create both; returning users update them with whatever Firebase reported (e.g. updated avatar).

5. **Server mints the internal JWT and a refresh token.** The JWT signs an `AuthContext` with `jwt.secret`, valid for `jwt.valid-for` (default 5 minutes). The refresh token is a row in `refresh_token` keyed by a UUID; the client gets back the row's UUID, the server keeps the rest (user-agent, IP, created-at, used-at).

6. **Result:** `200 { jwt, refreshToken, userCreated }`. `userCreated` is `true` when this call provisioned a new `User` account (first-time login), `false` for a returning user — clients use it to branch their UX (show onboarding vs. just log in). The status code is `200` either way; the "was a user created" signal lives in the body so a typed client (ts-rest, OpenAPI codegen) sees one response shape, not two keyed on status. Subsequent requests carry `Authorization: Bearer <jwt>`.

## Refreshing

The internal JWT is short-lived. When it expires, the client `POST /v1/auth/refresh-token` with the refresh-token UUID. `AuthenticationService.authenticateWithRefreshToken` looks up the row, verifies it hasn't been used or revoked, marks it `used`, and issues a fresh JWT + a fresh refresh token.

Refresh tokens are one-time-use — using one invalidates it. This means a stolen refresh token is only useful until the legitimate client refreshes again, at which point the legitimate client's refresh fails and the user has to log in. There's no time-based expiry on a refresh token today; only one-time-use plus revocation. Adding an `expires_at` column and a check in `RefreshToken.isValid` is the natural place to evolve if you want time bounds.

`cleanupExpiredRefreshTokensTask` runs daily at 1 AM to delete rows that have been used or revoked for more than 60 days (tombstone garbage collection — not active-token expiration).

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

- **`ExternalAuthVerifier` trait.** One method, one input/output type. Implement it against any provider that returns "this token represents this user with these attributes."
- **`AuthModule.externalAuthVerifier` is `protected lazy val`.** Override it in a sub-trait or in the concrete `ApplicationLoader`. Tests do this with `FakeAuthVerifier`. To replace Firebase with Auth0, swap this lazy val.
- **`AuthRouter` only knows about Firebase JWTs by name** (`POST /auth/firebase`). To support a new provider, add a new endpoint (`POST /auth/auth0`) that takes the relevant token shape, calls a different verifier method, and runs the same upsert-and-mint flow. The internal JWT is provider-agnostic.

For SSO-style protocols where the client redirects through the IdP (OIDC code flow, SAML), you'll need additional endpoints for the redirect handshake. The pattern stays the same once you have a verified token.

## What you can't do

- **Change the internal JWT's signing scheme to RS256/asymmetric.** `JwtService` uses HMAC. Easy to extend — provide a key pair and switch the algorithm — but not done out of the box.
- **Have multiple internal-JWT secrets / rotate them.** Single static secret. For rotation you'd add key-id support and a small key resolver in `JwtService`.
- **Federate identity across multiple providers per user.** `UserAuth` is one row per `(provider, providerUserId)`, and `User` has one canonical id. To allow "same user signs in via Google AND Apple," you'd link multiple `UserAuth` rows to one `User`.

None of these are deep changes; they're just not pre-built.

## Testing

`FakeAuthVerifier` returns a fixed `VerifiedExternalToken` regardless of input. `TestApplicationLoader` wires it in place of `FirebaseService`, so route specs can `POST /auth/firebase` with any string and get a predictable user back.

`TestData.authContext()` builds an `AuthContext` for service-level tests that don't go through the auth flow at all.

For an end-to-end test that exercises the JWT path: log in via the test loader, capture the returned JWT, send it as `Authorization: Bearer …` on the next request. The same `userAuthenticator` decodes it.

## Where to look next

- [http.md](http.md) — `AuthRouteProvider`, the gate, and how `AuthContext` flows into `route(auth)`.
- [scheduler.md](scheduler.md) — `cleanupExpiredRefreshTokensTask` is a recurring task; the same pattern applies to anything you need on a schedule.
- [error-handling.md](error-handling.md) — `AuthenticationResult` is one of the canonical Result-ADT examples.
