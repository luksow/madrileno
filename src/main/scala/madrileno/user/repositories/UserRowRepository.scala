package madrileno.user.repositories

import madrileno.user.domain.*
import madrileno.utils.db.dsl.*
import skunk.*
import skunk.codec.all.*

import java.net.URI
import java.time.Instant

case class UserRow(
  id: UserId,
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[URI],
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant],
  blockedAt: Option[Instant]) {
  def toUser: User = {
    import io.scalaland.chimney.dsl.*
    this.into[User].transform
  }
}

object UserRow {
  def apply(user: User): UserRow = {
    import io.scalaland.chimney.dsl.*
    user
      .into[UserRow]
      .withFieldConst(_.createdAt, InstantPlaceholder)
      .withFieldConst(_.updatedAt, InstantPlaceholder)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

object UserRowTable extends Table[UserRow]("\"user\"") with IdTable[UserRow, UserId] with TimestampedTable with SoftDeleteTable {
  override val id: Column[UserId]                 = column("id", uuid.as[UserId])
  val fullName: Column[Option[FullName]]          = column("full_name", text.as[FullName].opt)
  val emailAddress: Column[Option[EmailAddress]]  = column("email", text.as[EmailAddress].opt)
  val emailVerified: Column[Boolean]              = column("email_verified", bool)
  val avatarUrl: Column[Option[URI]]              = column("avatar_url", text.asConvertedTo[URI].opt)
  override val createdAt: Column[Instant]         = column("created_at", timestamptz.asInstant)
  override val updatedAt: Column[Instant]         = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  val blockedAt: Column[Option[Instant]]          = column("blocked_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[UserRow]) =
    (id, fullName, emailAddress, emailVerified, avatarUrl, createdAt, updatedAt, deletedAt, blockedAt)
}

case class UserRowFilter(
  id: SqlPredicate[UserId],
  emailAddress: SqlPredicate[Option[EmailAddress]],
  emailVerified: SqlPredicate[Boolean],
  deletedAt: SqlPredicate[Instant])
    extends SqlFilter {
  override def filterFragment: AppliedFragment = fromPredicatesAndSeparator(
    (
      id            -> UserRowTable.id,
      emailAddress  -> UserRowTable.emailAddress,
      emailVerified -> UserRowTable.emailVerified,
      deletedAt     -> UserRowTable.deletedAt
    ),
    SqlAnd
  )
}

class UserRowRepository
    extends IdRepository[UserRow, UserId](_.id)
    with TimestampedRepository[UserRow, UserId](
      (userRow, instant) => userRow.copy(createdAt = instant),
      (userRow, instant) => userRow.copy(updatedAt = instant)
    )
    with SoftDeleteRepository[UserRow, UserId]
    with FilteringRepository[UserRow, UserRowFilter] {

  override val table: UserRowTable.type = UserRowTable
}
