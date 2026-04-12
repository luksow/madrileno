package madrileno.utils.db.dsl

import cats.effect.IO
import skunk.Session
import skunk.implicits.sql

import java.time.Instant

extension [A, Id, F <: SqlFilter](repo: SoftDeleteRepository[A, Id] & FilteringRepository[A, F]) {
  def softDeleteByFilter(filter: F, deletedAt: Instant)(using session: Session[IO]): IO[Unit] = {
    val appliedFragment = filter.filterFragment
    session
      .execute(sql"UPDATE ${repo.table.n} SET ${repo.table.deletedAt.n} = ${repo.table.deletedAt.c} WHERE ${appliedFragment.fragment}".command)(
        (Some(deletedAt), appliedFragment.argument)
      )
      .void
  }
}
