# Events: transient bus vs durable outbox

madrileno has two ways to react to "something happened", with different guarantees. Pick by one question:

**Must someone act on this fact, eventually, guaranteed — even across a crash or deploy?**
- **Yes → the outbox** (`utils/events/outbox`): durable, at-least-once, exactly-once-effective for in-DB reactions.
- **No, it's best-effort live information → the event bus** (`utils/events/bus`): transient fan-out, at-most-once, dropped if nobody is listening.

The package tree is the map:

    utils/events/
      EventCodec.scala   -- the event contract: derives EventCodec (JSON, shared by both channels)
      bus/               -- transient channel
      outbox/            -- durable channel

## Event bus (`utils/events/bus`)

Postgres LISTEN/NOTIFY (or an in-memory topic in tests) behind `EventBus[E]`. Used for live WebSocket
push (`AuctionEvent`) and cross-node cache invalidation (`FeatureFlagEvent`). A missed message is
acceptable by design — a disconnected browser should not receive stale bids; a missed invalidation is
bounded by the cache TTL.

    enum AuctionEvent derives EventCodec { ... }

    val bus: EventBus[AuctionEvent] = eventBusRuntime.topic[AuctionEvent]("auction_events", maxQueued = 64)
    bus.publish(event)          // fire-and-forget, at-most-once
    bus.subscribe               // Stream[IO, AuctionEvent], live subscribers only

## Outbox (`utils/events/outbox`)

An append-only `domain_event` log written **in the same transaction** as your state change, so the
fact and the state commit atomically. Delivery to consumers is handled by the scheduler-backed
delivery machinery (see [outbox.md](outbox.md)) with retry, dead-lettering, and replay.

    // package madrileno.user.domain
    final case class UserAccountDeleted(userId: UserId) derives EventCodec
    object UserAccountDeleted {
      given DomainEventDescriptor[UserAccountDeleted] =
        DomainEventDescriptor("user-account-deleted.v1", "user", _.userId)
    }

    transactor.inTransaction {
      userRepository.anonymize(userId) *>
        outbox.publishTransactionally(UserAccountDeleted(userId))
    }

`eventType` is a versioned contract (`*.v1`): stored payloads live forever, so changes within a
version must be additive/tolerant, and breaking changes bump to `.v2` with the old decoder retained
(the full procedure — code shape, rollout order, hazards — is in [outbox.md](outbox.md#versioning-an-event)).

## How they relate

Both channels carry domain events serialized by one shared typeclass: `EventCodec`, which lives at the
`utils/events` root (Json-based; `derives EventCodec` declares event-ness once). Both subpackages
depend upward on the root and never on each other. The bus adds a topic and transient at-most-once
transport (the JSON travels as a string over NOTIFY); the outbox adds what durability requires — a
versioned type name and the aggregate identity — via `DomainEventDescriptor`. The same event type may
flow through both channels: publish to the outbox for the guaranteed reaction, and to the bus for the
live view.

Scheduler task payloads (`TaskDescriptor`) are deliberately NOT events — they are private
infrastructure plumbing and stay on raw circe. The rule: **domain event crossing a boundary →
`derives EventCodec`; private plumbing → raw circe.**
