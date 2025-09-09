package madrileno.auth.repositories

import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.{RefreshTokenId, *}
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import skunk.*
import skunk.codec.all.*

import java.time.Instant

case class RefreshTokenRow(
  id: RefreshTokenId,
  userId: UserId,
  userAgent: UserAgent,
  ipAddress: IpAddress,
  createdAt: Instant,
  usedAt: Option[Instant],
  deletedAt: Option[Instant]) {
  def toRefreshToken: RefreshToken = {
    import io.scalaland.chimney.dsl.*
    this.into[RefreshToken].transform
  }
}

object RefreshTokenRow {
  def apply(refreshToken: RefreshToken): RefreshTokenRow = {
    import io.scalaland.chimney.dsl.*
    refreshToken
      .into[RefreshTokenRow]
      .withFieldComputed(_.createdAt, _.createdAt.getOrElse(InstantPlaceholder))
      .withFieldConst(_.usedAt, None)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

object RefreshTokenRowTable
    extends Table[RefreshTokenRow]("refresh_token")
    with IdTable[RefreshTokenRow, RefreshTokenId]
    with TimestampedTable
    with SoftDeleteTable
    with ForeignIdTable[UserId] {
  override val id: Column[RefreshTokenId] = column("id", uuid.as[RefreshTokenId])
  val userId: Column[UserId]              = column("user_id", uuid.as[UserId])
  val userAgent: Column[UserAgent]        = column("user_agent", text.as[UserAgent])
  val ipAddress: Column[IpAddress] = column(
    "ip_address",
    text.imap(IpAddress.fromString.andThen(_.getOrElse(throw new IllegalStateException("Invalid IP address format"))))(_.toString)
  )
  override val createdAt: Column[Instant]         = column("created_at", timestamptz.asInstant)
  val usedAt: Column[Option[Instant]]             = column("used_at", timestamptz.asInstant.opt)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[UserId]  = userId
  override val updatedAt: Column[Instant] = createdAt

  override def mapping: (List[Column[?]], Codec[RefreshTokenRow]) = (id, userId, userAgent, ipAddress, createdAt, usedAt, deletedAt)
}

case class RefreshTokenRowFilter(
  id: SqlPredicate[RefreshTokenId] = p.any,
  userId: SqlPredicate[UserId] = p.any,
  userAgent: SqlPredicate[UserAgent] = p.any,
  usedAt: SqlPredicate[Instant] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {

  override def filterFragment: AppliedFragment = fromPredicatesAndSeparator(
    (
      id        -> RefreshTokenRowTable.id,
      userId    -> RefreshTokenRowTable.userId,
      usedAt    -> RefreshTokenRowTable.usedAt,
      deletedAt -> RefreshTokenRowTable.deletedAt
    ),
    SqlAnd
  )
}

class RefreshTokenRowRepository
    extends IdRepository[RefreshTokenRow, RefreshTokenId](_.id)
    with CreatedTimestampedRepository[RefreshTokenRow, RefreshTokenId]((refreshTokenRow, instant) => refreshTokenRow.copy(createdAt = instant))
    with SoftDeleteRepository[RefreshTokenRow, RefreshTokenId]
    with ForeignIdRepository[RefreshTokenRow, UserId]
    with FilteringRepository[RefreshTokenRow, RefreshTokenRowFilter] {

  override val table: RefreshTokenRowTable.type = RefreshTokenRowTable
}
