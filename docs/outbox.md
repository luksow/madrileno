# Transactional outbox

The outbox is the durable half of the events story ([events.md](events.md) explains how to choose between it and the transient bus). It answers one need: *module A changed state in a transaction; modules B and C must reliably react, even across a crash or deploy.*

The shape is the classic [transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html) with a per-consumer delivery ledger doing the job of an inbox table (à la NServiceBus/MassTransit): there is no broker, delivery rides on the [scheduler](scheduler.md), and consumers live in the same process. For a modular monolith that is the whole point — the guarantees come from Postgres transactions, not from infrastructure.

## The model

```
  your transaction                            delivery (one scheduler task per consumer)
┌───────────────────────────────┐           ┌──────────────────────────────────────────────┐
│ business writes               │           │ in ONE transaction:                          │
│ outbox.publishTransactionally │           │   lock ledger row FOR UPDATE                 │
│   → domain_event row          │           │   terminal status? → no-op                   │
│   → outbox_delivery row per   │  commit   │   run subscription handler                   │
│     subscribed consumer       │ ────────► │   Done → mark Completed                      │
│   → delivery task per         │           │   Drop → mark Failed (poison)                │
│     consumer (scheduled_task) │           │   Retry → raise → rollback → backoff         │
└───────────────────────────────┘           └──────────────────────────────────────────────┘

  OutboxRecovery (background fiber, every recovery-interval):
    a) events a subscribed consumer has no ledger row for  → open ledger row + task
    b) Pending ledger rows whose task vanished             → re-enqueue task
```

Two tables (`V3__outbox.sql`):

- **`domain_event`** — the append-only log: versioned `event_type` (`user-account-deleted.v1`), aggregate identity, `jsonb` payload, `occurred_at`. Rows are never updated or deleted.
- **`outbox_delivery`** — the ledger: one row per `(event_id, consumer)` with `status` (`Pending` / `Completed` / `Failed`), `last_error`, timestamps. This table is the delivery *memory*: a row in a terminal status is what stops an event from being processed twice.

## Publishing

```scala
final case class UserAccountDeleted(userId: UserId) derives EventCodec

object UserAccountDeleted {
  given DomainEventDescriptor[UserAccountDeleted] =
    DomainEventDescriptor("user-account-deleted.v1", "user", _.userId.unwrap)
}

transactor.inTransaction {
  userRepository.anonymize(userId, now) *>
    outbox.publishTransactionally(UserAccountDeleted(userId))
}
```

`publishTransactionally` is `DBInTransaction`, so the compiler forces you into a transaction: the event, its ledger rows, and its delivery tasks commit atomically with your state change, or none of it does. Publish only when a transition actually happened (the account-deletion flow publishes only when `anonymize` reports a transition) — that is what makes redelivered *commands* produce exactly one *event*.

Fan-out happens at publish time against the consumers registered in this process; consumers that only exist in a newer deploy are repaired by recovery (see below).

## Subscribing

A module contributes subscriptions the same way it contributes tasks or routes:

```scala
trait AuctionModule extends OutboxSubscriptionProvider {
  override abstract def outboxSubscriptions: List[OutboxSubscription] =
    super.outboxSubscriptions :+
      OutboxSubscription[UserAccountDeleted]("auction")(auctionAccountCleanupService.onAccountDeleted)
}
```

The consumer name (`"auction"`) is the ledger identity — renaming it means the consumer is treated as brand new (see replay below). Duplicate `(consumer, eventType)` registrations fail fast at wiring. A handler receives the decoded event (and optionally a `Delivery` with the attempt number) and returns a `Reaction`.

## The delivery contract

The handler runs *inside* the delivery transaction, after the ledger row is locked `FOR UPDATE` and confirmed non-terminal. What each `Reaction` means:

- **`Done`** — handler writes and the `Completed` mark commit together. This is the exactly-once-effective guarantee: for reactions expressed as DB writes, a crash at any point either commits everything or retries from scratch against the terminal-status guard.
- **`Retry(reason)`** — the transaction **rolls back**, including everything the handler wrote. The reason is recorded as `last_error` in a separate session (without touching the `Pending` status), and the scheduler retries with exponential backoff.
- **`Drop(reason)`** — the row is marked `Failed` with the reason, and — unlike `Retry` — **the handler's prior writes commit**. Use it for poison events (an undecodable payload is auto-dropped this way). If you don't want partial writes to survive a drop, decide to drop *before* writing.

Rules that follow from the handler running in a transaction:

- **Keep effects in the ambient session.** Repository calls, `mailer.sendTransactionally`, `schedulerClient.scheduleTransactionally` — all of these commit or roll back with the delivery and are therefore effectively exactly-once. A direct side effect (an HTTP call, a raw `IO`) executes on *every attempt*, including attempts that later roll back — that is plain at-least-once, and it's on you to make it idempotent.
- **The live bus is the one sanctioned exception.** Publishing to the [event bus](event-bus.md) from a handler (as `AuctionAccountCleanupService` does after cancelling an auction) goes out immediately, not on commit — on a rollback the push was early. That is within the bus's best-effort, at-most-once contract; the retry converges the real state. Don't use the bus from a handler for anything a client must not see early.
- **Delivery must never depend on wall-clock context.** The event carries `occurredAt`; a handler may run minutes or days later (retries, recovery, a consumer added after the fact). The auction cleanup consumer deliberately uses processing-time semantics — it cancels whatever is open *now*, not what was open at event time.

## What is deliberately NOT guaranteed

- **Ordering.** Every `(event, consumer)` delivery is an independent scheduler task. Two events for the same aggregate can be handled out of order, and two deliveries for the same consumer can run concurrently. This is a real divergence from CDC-based relays (Debezium et al. preserve per-key commit order) — it buys crash-safe simplicity on the scheduler we already have. Write handlers to be commutative or state-guarded (the auction cancel is a status-guarded transition: replaying or reordering it cannot un-cancel anything). If you need per-aggregate ordering, that is a design extension: serialize on the aggregate row with `FOR UPDATE` inside the handler, or add a per-aggregate sequence check and `Retry` until the predecessor lands.
- **Bounded latency.** Normal-path latency is one scheduler poll; after failures it's backoff-shaped; after a crash between commit and task pickup it can be up to `recovery-interval`.

## New consumers replay history

Recovery repairs "events a subscribed consumer has no ledger row for" — with no time bound. That means **subscribing a new consumer (or a new event type for an existing consumer) feeds it the entire history** of those event types, `recovery-batch-size` per `recovery-interval`. This is deliberate: it's the bootstrap story, and it's also why the ledger must never be half-pruned (below).

If a new consumer should *not* process history, seed its ledger rows as already-delivered in the same deploy that introduces it:

```sql
INSERT INTO outbox_delivery (event_id, consumer, status, last_error, created_at, updated_at)
SELECT id, 'my-consumer', 'Completed', NULL, now(), now()
FROM domain_event WHERE event_type IN ('user-account-deleted.v1')
ON CONFLICT DO NOTHING;
```

Mind the throughput arithmetic for real backfills: defaults (100 events / 5 min / consumer) drain ~29k events a day. Raise `recovery-batch-size` (or temporarily lower `recovery-interval`) for a large replay.

## Failures, dead-letters, and redrive

Delivery retries ride the scheduler's backoff. After `delivery-max-retries` consecutive failures the task is abandoned and the ledger row goes to `Failed` **keeping the causal `last_error`** — that is the dead-letter queue, and it's just rows:

```sql
SELECT event_id, consumer, last_error, updated_at
FROM outbox_delivery WHERE status = 'Failed' ORDER BY updated_at DESC;
```

To redrive after fixing the cause, flip the rows back — recovery re-enqueues them within one `recovery-interval`:

```sql
UPDATE outbox_delivery SET status = 'Pending', updated_at = now()
WHERE consumer = 'auction' AND status = 'Failed';
```

Alert on dead-letter growth. The signals: the `scheduler.executions` counter with `outcome="retries_exhausted"` and `task.name="outbox-deliver:<consumer>"` fires once per abandonment; a `count(*)` on `Failed` rows is the queue-depth check. Removing a cap (`delivery-max-retries` absent → retry forever) trades dead-letters for indefinite retry noise — pick per deployment.

## Recovery, deploys, multiple nodes

The `OutboxRecovery` fiber (started in `Main`) runs every `recovery-interval` and repairs exactly two situations: events a subscribed consumer never got a ledger row for (consumer registered after publish, or publish raced a deploy), and `Pending` rows whose scheduler task vanished (e.g. the scheduler deleted an undecodable task row). Pending rows for a consumer that is no longer registered are logged and left alone so a removed module can drain gracefully after a rollback or a deliberate decommission.

All repair actions are safe to race: opening a ledger row is `ON CONFLICT DO NOTHING`, task scheduling is an upsert that leaves running rows alone, and the terminal-status guard makes a duplicate task a no-op. Several nodes running recovery concurrently just do redundant reads.

## Retention

- **`domain_event` is forever, by design** — it is the audit log, and `event_type` is a versioned contract (additive changes within `.v1`, breaking changes bump to `.v2` with the old decoder retained). The corollary: **payloads must not contain data you may be obliged to erase.** `UserAccountDeleted(userId)` carries only the id — keep it that way; if an event needs personal data, reference it, don't embed it (or you're into crypto-shredding territory).
- **`outbox_delivery` is the dedup memory, not a scratch table.** A `Completed` row is the only thing telling recovery not to re-deliver that event. Never prune the ledger alone while the events remain and the consumer still subscribes to the type — recovery would faithfully re-deliver all of it. If unbounded growth becomes a problem, prune *event and ledger rows together* (ledger first — it has the FK) for events older than your horizon whose deliveries are all terminal; accept that replay-from-history stops working past that horizon.

## Configuration

```hocon
outbox {
  recovery-interval    = 5m    # recovery sweep cadence (also the redrive/backfill cadence)
  recovery-batch-size  = 100   # repaired events per consumer per sweep
  delivery-max-retries = 10    # per-delivery attempts before dead-lettering; remove to retry forever
}
```

Each key has an `OUTBOX_*` environment override (`application.conf`).

## Where to look

- `utils/events/outbox/` — `Outbox` (publish), `OutboxDispatcher` (delivery), `OutboxRecovery`, repositories; ~400 lines total, built to be read.
- The worked example: `DELETE /v1/users/me` → `AccountService.deleteAccount` publishes `UserAccountDeleted`; the `auction` consumer (`AuctionAccountCleanupService`) cancels open auctions, pushes the live event, and mails bidders. The auth side (anonymization, token revocation, the residual access-token window, and the industry-standard extensions this template deliberately leaves out) is documented in [auth.md](auth.md).
- Tests: `OutboxSpec` (publish atomicity), `OutboxDispatcherSpec` (the Reaction contract), `OutboxEndToEndSpec` (through a live scheduler), `OutboxRecoverySpec`, `SchedulerOnAbandonSpec` (dead-letter mechanics), `AuctionAccountCleanupSpec` / `AccountServiceSpec` (the worked example).
