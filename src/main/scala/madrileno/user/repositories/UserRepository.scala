package madrileno.user.repositories

import cats.effect.{Clock, IO}
import madrileno.user.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
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

  def update(user: User): UserRow = {
    import io.scalaland.chimney.dsl.*
    this.patchUsing(user)
  }
}

object UserRow {
  def apply(user: User, now: Instant): UserRow = {
    import io.scalaland.chimney.dsl.*
    user
      .into[UserRow]
      .withFieldConst(_.createdAt, now)
      .withFieldConst(_.updatedAt, now)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

object UserRowTable extends Table[UserRow]("\"user\"") with IdTable[UserRow, UserId] with SoftDeleteTable {
  override val id: Column[UserId]                 = column("id", uuid.as[UserId])
  val fullName: Column[Option[FullName]]          = column("full_name", text.as[FullName].opt)
  val emailAddress: Column[Option[EmailAddress]]  = column("email", text.as[EmailAddress].opt)
  val emailVerified: Column[Boolean]              = column("email_verified", bool)
  val avatarUrl: Column[Option[URI]]              = column("avatar_url", text.asConvertedTo[URI].opt)
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)
  val blockedAt: Column[Option[Instant]]          = column("blocked_at", timestamptz.asInstant.opt)

  def mapping: (List[Column[?]], Codec[UserRow]) =
    (id, fullName, emailAddress, emailVerified, avatarUrl, createdAt, updatedAt, deletedAt, blockedAt)
}

case class UserRowFilter(
  id: SqlPredicate[UserId] = p.any,
  emailAddress: SqlPredicate[Option[EmailAddress]] = p.any,
  emailVerified: SqlPredicate[Boolean] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (UserRowTable.id, UserRowTable.emailAddress, UserRowTable.emailVerified, UserRowTable.deletedAt))
}

class UserRepository(using Clock[IO]) {
  def save(user: User): DB[User] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      val row = UserRow(user, now)
      repository.create(row).as(row.toUser)
    }
  }

  def get(id: UserId): DB[User] = {
    repository.getById(id).map(_.toUser)
  }

  def update(id: UserId, f: User => User): DB[Unit] = {
    repository.updateById(id, userRow => userRow.update(f(userRow.toUser)))
  }

  private val repository: IdRepository[UserRow, UserId] & SoftDeleteRepository[UserRow, UserId] & FilteringRepository[UserRow, UserRowFilter] =
    new IdRepository[UserRow, UserId](_.id) with SoftDeleteRepository[UserRow, UserId] with FilteringRepository[UserRow, UserRowFilter] {
      override val table: UserRowTable.type = UserRowTable
    }
}
