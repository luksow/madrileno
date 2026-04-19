package madrileno.utils.db.transactor

import cats.effect.{IO, Resource}
import skunk.*
import skunk.util.Origin

type DB[A]              = Session[IO] ?=> IO[A]
type DBInTransaction[A] = Session[IO] ?=> Transaction[IO] ?=> IO[A]

trait Transactor {

  /** Runs `f` within a single database transaction. All operations share the same session and transaction. */
  def inTransaction[A](f: DBInTransaction[A]): IO[A]

  def savepoint(using
    transaction: Transaction[IO],
    origin: Origin
  ): IO[transaction.Savepoint] = {
    transaction.savepoint
  }

  /** Runs `f` with a session from the pool. Each `inSession` call gets its own session — multiple `inSession` calls are NOT transactional together.
    * Use `inTransaction` for atomicity.
    */
  def inSession[A](f: DB[A]): IO[A]

  /** Raw session resource for callers that need to hold a session open beyond the scope of a single block — e.g. Postgres LISTEN, which requires a
    * dedicated long-lived session. Prefer `inSession` / `inTransaction` for anything else.
    */
  def sessions: Resource[IO, Session[IO]]
}
