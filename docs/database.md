# Database

The data layer is Postgres + skunk + Flyway, sitting under a small repository DSL that gives you `create`/`find`/`update`/`delete` for typical tables without writing SQL by hand.

You wrote SQL when you need it — for joins, aggregations, custom updates — but you don't write it for things every table needs.

## The pieces

| Layer        | Library / file                  | What it does                                                 |
| ------------ | ------------------------------- | ------------------------------------------------------------ |
| Driver       | skunk                           | Pure-Scala async Postgres client. No JDBC, no blocking.       |
| Pool         | `PgTransactor.resource`         | Built on skunk's session pool. `Main` constructs it as a `Resource`. |
| Sessions     | `Transactor.{inSession, inTransaction}` | Acquires a session from the pool and runs `DB[A]` / `DBInTransaction[A]` blocks. |
| Migrations   | Flyway                          | One-shot via `sbt flywayMigrate`. Migrations live under `src/main/resources/db/migration/`. |
| DSL          | `madrileno.utils.db.dsl`        | `Table`, `Column`, the four `*Repository` traits, codec helpers. |

## Sessions vs transactions

Two type aliases capture the difference:

```scala
type DB[A]              = Session[IO] ?=> IO[A]
type DBInTransaction[A] = Session[IO] ?=> Transaction[IO] ?=> IO[A]
```

A `DB[A]` needs a session. A `DBInTransaction[A]` needs a session AND an open transaction.

```scala
transactor.inSession {
  for {
    a <- repo.find(idA)        // DB[Option[A]] — runs in autocommit mode
    b <- repo.find(idB)        // DB[Option[B]] — separate query, same session
  } yield (a, b)
}                              // → IO[(Option[A], Option[B])]

transactor.inTransaction {
  for {
    _ <- repo.findForUpdate(id) // DBInTransaction — takes a row lock
    _ <- repo.update(updated)
  } yield ()
}                              // → IO[Unit] — atomic
```

`inSession` doesn't open a transaction. Each statement autocommits. Multiple `inSession` calls do NOT compose into a transaction — each one acquires a fresh session from the pool.

`inTransaction` opens `BEGIN`, runs the block, and `COMMIT`s. Any exception inside `ROLLBACK`s. `findForUpdate` (row-level locking) requires an active transaction and won't compile from a `DB`-only context — the implicit `Transaction[IO]` won't be in scope.

A repo method that always must be in a transaction (e.g., `bulkSetPositions`'s two-phase update) returns `DBInTransaction[A]`. A method that can run either way returns `DB[A]`.

## Defining a table

A `Table[Row]` is a singleton object describing the table's columns and codecs. The `Row` is a private case class that mirrors the row layout exactly. The domain type lives elsewhere; converting between the two is the row's responsibility.

```scala
private[repositories] final case class AuctionImageRow(
  id: AuctionImageId,
  auctionId: AuctionId,
  storageKey: StorageKey,
  fileName: String,
  contentType: `Content-Type`,
  sizeBytes: SizeBytes,
  position: ImagePosition,
  uploadedAt: Instant,
  deletedAt: Option[Instant],
  // … new analyzer fields
) {
  def toAuctionImage: AuctionImage = this.into[AuctionImage].transform   // chimney
}

private[repositories] object AuctionImageRowTable
    extends Table[AuctionImageRow]("auction_image")
    with IdTable[AuctionImageRow, AuctionImageId]
    with SoftDeleteTable
    with ForeignIdTable[AuctionId] {

  override val id: Column[AuctionImageId]         = column("id", uuid.as[AuctionImageId])
  val auctionId: Column[AuctionId]                = column("auction_id", uuid.as[AuctionId])
  val storageKey: Column[StorageKey]              = column("storage_key", text.as[StorageKey])
  // …
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  override val foreignId: Column[AuctionId]       = auctionId

  def mapping: (List[Column[?]], Codec[AuctionImageRow]) =
    (id, auctionId, storageKey, fileName, contentType, sizeBytes,
     position, uploadedAt, deletedAt, …)
}
```

Three things to know:

- **Codec adapters.** `uuid.as[AuctionImageId]` lifts skunk's `Codec[UUID]` to `Codec[AuctionImageId]` for the opaque type. `text.as[StorageKey]`, `int4.as[ImagePosition]`, etc. all do the same. Enum columns use `text.asEnum[FormatEnum]`. See `dsl.scala` for the full set of helpers.
- **`mapping`** is the heart of the table. It enumerates the columns in row order and produces the row codec via twiddle types. Adding a column is one line in the case class, one column declaration, and one tuple entry in `mapping`.
- **Implementing the marker traits** (`IdTable`, `SoftDeleteTable`, `ForeignIdTable`) is what unlocks the corresponding repository.

## The repository traits

Each trait gives you a vocabulary of operations.

| Trait                                   | Adds                                                        |
| --------------------------------------- | ----------------------------------------------------------- |
| `IdRepository[A, Id]`                   | `create`, `createAll`, `upsert`, `findById`, `findByIds`, `getById`, `existsById`, `update`, `updateById`, `deleteById` |
| `SoftDeleteRepository[A, Id]`           | extends `IdRepository`. Adds `softDeleteById`, `softDeleteByIds`, `softDeleteAll`, `restoreById`, `findByIdWithDeleted`, `existsByIdWithDeleted`, `purgeDeletedBefore`. Also overrides `baseFilter` so all `find*` ignore soft-deleted rows automatically. |
| `ForeignIdRepository[A, Id]`            | `findByForeignId`, `findByForeignIds`, `countByForeignId`, `existsByForeignId`, `deleteByForeignId`. Pulls children of a parent by FK. |
| `FilteringRepository[A, F <: SqlFilter]`| `findByFilter`, `findOneByFilter`, `getByFilter`, `existsByFilter`, `countByFilter`. Scoped queries with a typed `RowFilter`. |

Compose them as needed. `AuctionImageRepository`'s underlying `repository` extends all four:

```scala
private val repository:
    IdRepository[AuctionImageRow, AuctionImageId]
  & SoftDeleteRepository[AuctionImageRow, AuctionImageId]
  & ForeignIdRepository[AuctionImageRow, AuctionId]
  & FilteringRepository[AuctionImageRow, AuctionImageRowFilter] =
  new IdRepository[…](_.id)
    with SoftDeleteRepository[…]
    with ForeignIdRepository[…]
    with FilteringRepository[…] {
    override val table = AuctionImageRowTable
  }
```

This `repository` is private. The public `AuctionImageRepository` class exposes domain-shaped methods — `find(id): DB[Option[AuctionImage]]`, `listByAuction(auctionId)`, `markAnalyzed(id, …)` — that delegate to `repository.findById`, `repository.findByForeignId`, `repository.updateById`, etc.

This separation keeps the public surface in domain vocabulary, not SQL. Callers compose `Option[ProductName]` and friends; they never see `RowFilter` or `SqlPredicate`.

## Filters

`SqlFilter` is the contract for a filter object that knows its `filterFragment`, an optional `orderByFragment`, and pagination. A row-filter is a private case class keyed by typed predicates:

```scala
private[repositories] final case class AuctionImageRowFilter(
  id: SqlPredicate[AuctionImageId] = p.any,
  auctionId: SqlPredicate[AuctionId] = p.any,
  deletedAt: SqlPredicate[Instant] = p.isNull
) extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(
      this,
      (AuctionImageRowTable.id, AuctionImageRowTable.auctionId, AuctionImageRowTable.deletedAt)
    )
  override def orderByFragment: Fragment[Void] = sql"${AuctionImageRowTable.position.n} ASC"
}
```

Predicate constructors live on `p`: `p.equal(x)`, `p.in(xs)`, `p.greaterThan(x)`, `p.between(x, y)`, `p.like("%foo%")`, `p.notNull`, `p.isNull`, `p.any` (no constraint). Every field defaults to `p.any` so callers fill in only what they want.

Public list/find methods take typed parameters and build the filter internally:

```scala
def listByAuction(auctionId: AuctionId): DB[List[AuctionImage]] =
  repository.findByFilter(
    AuctionImageRowFilter(auctionId = p.equal(auctionId))
  ).map(_.map(_.toAuctionImage))
```

Default filters are safe-by-default. `deletedAt = p.isNull` means soft-deleted rows are hidden unless a caller opts in via `findByIdWithDeleted`.

## Updating

Three flavours, increasingly opinionated:

```scala
// 1. Whole-row replace. You produce the new row.
repository.update(updatedRow)

// 2. Read-modify-write under FOR UPDATE. The repo loads the row, locks it,
//    applies your transform, and writes back. Composes nicely with sealed-monad
//    when the transform can fail.
repository.updateById(id, _.copy(width = Some(w), analyzedAt = Some(now)))

// 3. Hand-rolled SQL. For multi-row updates, joins, or anything the helpers
//    don't cover.
sql"UPDATE … SET … WHERE …".command
```

For single-row updates, prefer `updateById(id, transform)`. It's atomic, takes the lock for you, and reads as a domain-level operation.

## The two-phase reordering trick

Some operations can't be expressed as one statement because of constraints. Reordering positions is the canonical example: swapping `[A=0, B=1]` to `[A=1, B=0]` would violate the `(auction_id, position) WHERE deleted_at IS NULL` partial unique index mid-transaction.

`AuctionImageRepository.bulkSetPositions` does it in two phases inside one `inTransaction`:

1. Move every targeted row to a unique negative slot (`-(idx + 1)` per loop index). The partial unique allows it because no other row uses negative positions.
2. Move every targeted row to its final position. By now the original positions have been vacated.

The `ImagePosition` opaque type still rejects negatives in the domain — the negative integers exist only as raw `Int`s inside the repo helper, never as domain values.

## Migrations

Migrations are SQL files under `src/main/resources/db/migration/`, named `V<n>__<description>.sql`. Flyway runs them in order on startup (in tests via Testcontainers; in dev via `sbt flywayMigrate`). The application **does not auto-migrate at boot** — production deploys run migrations as a separate step.

Three rules:

1. **Never modify a migration that's been merged to `main`.** Flyway hashes every applied script; changing one breaks startup with a checksum error in any environment that already ran it. If you need to change something, add a new `V(n+1)__…` migration.
2. **Migrations are forward-only.** The template doesn't ship a "down" migration mechanism. If you need to undo something, write a new migration that does the undoing.
3. **Schema changes are domain changes.** Adding a column means updating `Row`, `RowTable.mapping`, and the domain class together. Don't ship one without the others.

For the rare case where you need to back out a bad migration in dev: `docker compose down -v` wipes the volume; `sbt flywayMigrate` reapplies from scratch.

## Connection pool and config

`PgConfig` is loaded from the `pg.*` config block. Defaults: pool size 10, no SSL, default skunk caches (1024 each). Override per-environment via env vars (`PG_HOST`, `PG_PORT`, `PG_PASSWORD`, …).

The pool is shared across the application — there is no per-module pool. Sessions return to the pool when their `inSession` / `inTransaction` block completes, including on errors.

## Testing

`TestTransactor` (mixed into specs) starts a Postgres testcontainer, runs Flyway migrations, and exposes the same `Transactor` interface as production. `withRollback { … }` runs a test inside a transaction that rolls back at the end, so tests don't leak data between runs. See [testing-guide.md](testing-guide.md).

## Where to look next

- [domain-modeling.md](domain-modeling.md) — opaque types, smart constructors, Row-vs-domain separation.
- [adding-a-module.md](adding-a-module.md) — full vertical slice including a migration + `Row` + repository class.
- [sealed-monad.md](sealed-monad.md) — composing repository operations inside a sealed-monad for-comprehension.
