# Testing Playbook

## Philosophy

- **Comprehensive**: test real business logic, not just happy paths
- **Real infrastructure where it buys confidence**: real DB (Testcontainers), real SMTP (Mailpit), real HTTP (Baklava). Use fakes at unstable or expensive external boundaries (Firebase)
- **Demonstrate patterns**: each test suite shows users of the template how to test their own code
- **Fast feedback**: transaction-rollback per test for DB isolation where appropriate

## Testing Layers

### Layer 1: Unit Tests

Pure functions, no IO, no DB, no external services.

**What to test:**
- Domain logic: `RefreshToken.isValid`, `User.isActive`, `User.withUpdatedProfile`
- JWT encoding/decoding round-trip, expiry validation
- Cron expression parsing and next-time computation
- Email template rendering: subject, greeting, CTA URL, language-dependent text (not full HTML snapshots)

**Infrastructure:** ScalaTest only.

**Files:**
```
src/test/scala/madrileno/
├── auth/
│   ├── domain/RefreshTokenSpec.scala
│   └── services/JwtServiceSpec.scala
├── auth/emails/
│   └── WelcomeEmailTemplateSpec.scala
├── user/
│   └── domain/UserSpec.scala
└── utils/task/
    └── CronExpressionSpec.scala
```

### Layer 2: Integration Tests (services + repositories, real DB)

Real PostgreSQL via Testcontainers, Flyway migrations, transaction-rollback isolation.

**What to test:**

*Repositories:*
- CRUD: create → read → update → soft-delete → verify invisible → verify WithDeleted
- Filter queries: construct filters, verify correct rows returned
- `baseFilter` enforcement: updates respect it, hard deletes bypass it
- `deleteUsedOrDeletedBefore`: only deletes used/deleted tokens, not active ones

*Services (with real DB):*
- `AuthenticationService`: full user creation flow (create user + userAuth + tokens + welcome email scheduled transactionally)
- `AuthenticationService`: refresh token rotation (use token → old invalidated → new issued)
- `AuthenticationService`: revocation (revoke by ID, revoke by user-agent)
- Token lifecycle: issue → use → verify used token rejected → verify new token works
- Scheduler: save task → pick → execute → verify completion/reschedule/failure handling
- **Transactional mail scheduling**: verify that when user creation transaction rolls back, the scheduled email is also rolled back (this was a real bug we fixed — `send` vs `sendTransactionally`)
- **Mail serialization round-trip**: verify that a queued HTML email survives JSON serialization intact (MailBody → SerializedMailBody → JSON → decode → SMTP)

**Infrastructure:**
- Testcontainers (PostgreSQL) + Flyway
- Deterministic `Clock[IO]` and `UUIDGen[IO]` via cats-effect-testing

**Transaction isolation — two modes:**

*Default: rollback-per-test*
- Each test runs inside a transaction that rolls back — zero cleanup, tests can't pollute each other
- Suitable for most repository and service tests

*Explicit: no-rollback mode*
- For tests that verify cross-session or cross-transaction behavior
- Required when: the code under test opens its own sessions (e.g., `mailer.send` via `SchedulerClient.schedule`), or when testing that `sendTransactionally` actually participates in the caller's transaction
- These tests must clean up after themselves (truncate or delete test data)

The no-rollback mode is not an escape hatch — it's a first-class testing pattern for this codebase, because several real bugs lived at the transaction boundary.

**New dependencies:**
```scala
"com.dimafeng" %% "testcontainers-scala-scalatest"  % testcontainersV % "test"
"com.dimafeng" %% "testcontainers-scala-postgresql"  % testcontainersV % "test"
```

**Files:**
```
src/test/scala/madrileno/
├── support/
│   ├── TestTransactor.scala              — Testcontainers + Flyway + rollback/no-rollback modes
│   ├── TestData.scala                    — builders for domain objects
│   └── FakeFirebaseVerifier.scala        — fake for external auth verification
├── auth/
│   ├── repositories/RefreshTokenRepositorySpec.scala
│   ├── repositories/UserAuthRepositorySpec.scala
│   └── services/AuthenticationServiceSpec.scala
├── user/
│   └── repositories/UserRepositorySpec.scala
└── utils/
    ├── task/SchedulerIntegrationSpec.scala
    └── mailer/MailSerializationSpec.scala
```

### Layer 3: API Tests (Baklava)

Route tests using Baklava — tests routes AND generates OpenAPI documentation from the test definitions.

**What to test:**
- Auth routes: firebase login, refresh token, session management
- Error response format (RFC 7807 structure)
- Route-level validation: missing fields → 400, wrong types → 400
- Health check endpoint
- Mail preview routes (dev mode): index returns HTML list, preview renders email HTML, 404 for unknown, language selector works

**Infrastructure:** Baklava (http4s integration) + Testcontainers for DB-backed routes

**New dependency:**
```scala
"pl.iterators" %% "baklava-http4s" % baklavaV % "test"
```

**Files:**
```
src/test/scala/madrileno/
├── support/
│   └── BaseRouteSpec.scala               — Baklava base class with DB setup
├── auth/routers/
│   └── AuthRouterSpec.scala
├── healthcheck/routers/
│   └── HealthCheckRouterSpec.scala
└── utils/mailer/
    └── MailPreviewRouterSpec.scala
```

### Layer 4: SMTP Tests (Mailpit)

Real SMTP delivery via Mailpit (Docker). Verify actual MIME structure, not serialization.

**What to test:**
- Plain text email: correct content-type, body content
- HTML email: renders correctly, correct subject
- Both (multipart/alternative): text and HTML parts present
- Attachments: file present, correct filename and content-type
- Inline attachments: Content-ID header, image referenced in HTML

Note: delivery timing (`at`/`in` parameters) is a scheduler concern, not SMTP. Test delay semantics in scheduler integration tests with a controllable clock.

**Infrastructure:** Mailpit via Testcontainers (SMTP on random port, REST API for verification)

**Files:**
```
src/test/scala/madrileno/
└── utils/mailer/
    └── SmtpSenderSpec.scala
```

## Why Integration Tests for Services, Not Fakes

DDD purists advocate testing services with fake repositories (in-memory implementations of repo traits). This tests orchestration logic in isolation, is fast, and doesn't need Docker.

We chose **integration tests with a real DB** instead, because:

- **Our services are mostly orchestration** — they call repos, check conditions, call other services. The "logic" is the flow, not complex computation. Testing with fakes would verify "we called save after create" which is testing the code, not the behavior.
- **The real bugs live at the boundary** — JWT not expiring, filters deleting all tokens, `updatedAt` not refreshing, `send` vs `sendTransactionally` — all were service↔DB boundary issues. Fakes wouldn't have caught them.
- **Fakes are a second implementation** — they must be maintained alongside the real repos and can silently diverge (SQL edge cases, constraint violations, transaction semantics).
- **Transaction rollback makes integration tests fast** — no cleanup, no Docker restart. The speed difference vs fakes is small.

**Exception:** If a service grows complex domain logic (pricing calculations, state machines, workflow rules), extract that logic into pure domain functions and unit test those. Don't force integration tests on pure computation.

## What NOT to Test

- Framework code (http4s, circe, skunk) — trust the libraries
- Macwire wiring — if it compiles, it works
- Configuration loading — pureconfig is well-tested
- Firebase integration directly — external service, introduce a test seam (see below)

## Shared Test Infrastructure

### TestTransactor

```
Testcontainers PostgreSQL → Flyway migrations → Skunk session pool
Default mode: BEGIN → run test → ROLLBACK
No-rollback mode: run test → explicit cleanup
```

- Starts once per test suite (shared container)
- Runs all Flyway migrations on startup
- Default: wraps each test in a transaction that rolls back
- No-rollback mode for cross-session/cross-transaction tests
- Provides `DB` and `DBInTransaction` context for repository/service calls

### Deterministic Time and UUIDs

Use `cats-effect-testing` (`TestControl`) and custom givens to make time and UUIDs deterministic in tests:

- **Clock**: `TestControl` provides a controllable `Clock[IO]` — advance time explicitly, assert on exact timestamps (e.g., JWT expiry = start + validFor, `updatedAt` = exact test time)
- **UUIDGen**: provide a `given UUIDGen[IO]` that returns predictable UUIDs — useful for asserting on task instance IDs, mail IDs, or anywhere `UUIDGen[IO].randomUUID` is called

This eliminates flaky time-window assertions and makes tests reproducible.

### Firebase Test Seam

`FirebaseService` is a concrete class over `FirebaseAuth` — `new FirebaseService(null)` is not a safe fake. Before writing auth service tests:

1. Extract a trait: `trait ExternalAuthVerifier { def verifyToken(token: String): IO[Either[Throwable, VerifiedExternalToken]] }`
2. `FirebaseService` implements it
3. Tests provide a fake implementation that returns configurable results
4. `AuthenticationService` depends on the trait, not the concrete class

This is a prerequisite for `AuthenticationServiceSpec`.

### TestData Builders

Helpers to create valid domain objects without specifying every field:

```scala
object TestData {
  def user(id: UserId = randomUserId(), fullName: Option[FullName] = Some(FullName("Test User")), ...): User
  def refreshToken(userId: UserId, ...): RefreshToken
}
```

## Execution

```bash
# All tests
sbt test

# Specific suite
sbt "testOnly *RefreshTokenRepositorySpec"

# Unit tests only (no Docker needed)
sbt "testOnly * -- -l Integration"

# Integration tests only
sbt "testOnly * -- -n Integration"
```

## Implementation Order

1. **TestTransactor + deterministic time/UUID support** — foundation
2. **Firebase test seam** — prerequisite for service tests
3. **AuthenticationServiceSpec** — user creation + transactional mail scheduling
4. **MailSerializationSpec** — queued HTML survives JSON round-trip
5. **SmtpSenderSpec** — MIME structure via Mailpit
6. **UserRepositorySpec** — CRUD + soft-delete + updatedAt
7. **RefreshTokenRepositorySpec** — filters, cleanup job
8. **Unit: JwtServiceSpec** — encode/decode, expiry
9. **Unit: RefreshTokenSpec + UserSpec** — domain logic
10. **Unit: WelcomeEmailTemplateSpec + CronExpressionSpec**
11. **API: HealthCheckRouterSpec** — Baklava example
12. **API: AuthRouterSpec** — auth routes with Baklava
13. **API: MailPreviewRouterSpec** — preview routes
