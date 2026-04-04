package madrileno.auth.repositories

import cats.effect.IO
import com.comcast.ip4s.IpAddress
import madrileno.auth.domain.*
import madrileno.user.domain.UserId
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

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
      .transform
  }
}

object RefreshTokenRowTable
    extends Table[RefreshTokenRow]("refresh_token")
    with IdTable[RefreshTokenRow, RefreshTokenId]
    with SoftDeleteTable
    with ForeignIdTable[UserId] {
  override val id: Column[RefreshTokenId] = column("id", uuid.as[RefreshTokenId])
  val userId: Column[UserId]              = column("user_id", uuid.as[UserId])
  val userAgent: Column[UserAgent]        = column("user_agent", text.as[UserAgent])
  val ipAddress: Column[IpAddress] = column(
    "ip_address",
    text.imap(IpAddress.fromString.andThen(_.getOrElse(throw new IllegalStateException("Invalid IP address format"))))(_.toString)
  )
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val usedAt: Column[Option[Instant]]             = column("used_at", timestamptz.asInstant.opt)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[UserId] = userId

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
      userAgent -> RefreshTokenRowTable.userAgent,
      usedAt    -> RefreshTokenRowTable.usedAt,
      deletedAt -> RefreshTokenRowTable.deletedAt
    ),
    SqlAnd
  )
}

class RefreshTokenRepository {
  def save(refreshToken: RefreshToken): DB[RefreshToken] = {
    repository.create(RefreshTokenRow(refreshToken)).map(_.toRefreshToken)
  }

  def listActive(userId: UserId): DB[List[RefreshToken]] = {
    repository.findByFilter(RefreshTokenRowFilter(userId = p.equal(userId))).map(_.map(_.toRefreshToken).filter(_.isValid))
  }

  def listActiveForUpdate(userId: UserId, userAgent: UserAgent): DBInTransaction[List[RefreshToken]] = {
    repository
      .findByFilter(RefreshTokenRowFilter(userId = p.equal(userId), userAgent = p.equal(userAgent)), Lock.ForUpdate)
      .map(_.map(_.toRefreshToken).filter(_.isValid))
  }

  def findForUpdate(id: RefreshTokenId): DBInTransaction[Option[RefreshToken]] = {
    repository.findOneByFilter(RefreshTokenRowFilter(id = p.equal(id)), Lock.ForUpdate).map(_.map(_.toRefreshToken))
  }

  def update(id: RefreshTokenId, f: RefreshToken => RefreshToken): DB[Unit] = {
    repository.updateById(id, row => RefreshTokenRow(f(row.toRefreshToken)))
  }

  def update(refreshToken: RefreshToken): DB[Unit] = {
    repository.update(RefreshTokenRow(refreshToken))
  }

  def deleteUsedOrDeletedBefore(cutoff: Instant): DB[Unit] = {
    val session = summon[Session[IO]]
    val table   = RefreshTokenRowTable
    session
      .execute(sql"""DELETE FROM ${table.n}
          WHERE ${table.usedAt.n} < ${table.usedAt.c}
             OR ${table.deletedAt.n} < ${table.deletedAt.c}
        """.command)((Some(cutoff), Some(cutoff)))
      .void
  }

  private val repository: IdRepository[RefreshTokenRow, RefreshTokenId] & SoftDeleteRepository[RefreshTokenRow, RefreshTokenId] & ForeignIdRepository[
    RefreshTokenRow,
    UserId
  ] & FilteringRepository[RefreshTokenRow, RefreshTokenRowFilter] =
    new IdRepository[RefreshTokenRow, RefreshTokenId](_.id)
      with SoftDeleteRepository[RefreshTokenRow, RefreshTokenId]
      with ForeignIdRepository[RefreshTokenRow, UserId]
      with FilteringRepository[RefreshTokenRow, RefreshTokenRowFilter] {

      override val table: RefreshTokenRowTable.type = RefreshTokenRowTable
    }
}
