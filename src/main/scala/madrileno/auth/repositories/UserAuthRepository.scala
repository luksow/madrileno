package madrileno.auth.repositories

import cats.effect.{Clock, IO}
import madrileno.auth.domain.*
import madrileno.user.domain.*
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*

import java.time.Instant

case class UserAuthRow(
  id: UserAuthId,
  userId: UserId,
  provider: Provider,
  providerUserId: ProviderUserId,
  credential: Credential,
  metadata: Metadata,
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Option[Instant]) {
  def toUserAuth: UserAuth = {
    import io.scalaland.chimney.dsl.*
    this.into[UserAuth].transform
  }
}

object UserAuthRow {
  def apply(userAuth: UserAuth, now: Instant): UserAuthRow = {
    import io.scalaland.chimney.dsl.*
    userAuth
      .into[UserAuthRow]
      .withFieldConst(_.createdAt, now)
      .withFieldConst(_.updatedAt, now)
      .withFieldConst(_.deletedAt, None)
      .transform
  }
}

object UserAuthRowTable
    extends Table[UserAuthRow]("user_auth")
    with IdTable[UserAuthRow, UserAuthId]
    with SoftDeleteTable
    with ForeignIdTable[UserId] {
  override val id: Column[UserAuthId]             = column("id", uuid.as[UserAuthId])
  val userId: Column[UserId]                      = column("user_id", uuid.as[UserId])
  val provider: Column[Provider]                  = column("provider", text.asEnum[Provider])
  val providerUserId: Column[ProviderUserId]      = column("provider_user_id", text.as[ProviderUserId])
  val credential: Column[Credential]              = column("credential", text.as[Credential])
  val metadata: Column[Metadata]                  = column("metadata", jsonb.as[Metadata])
  val createdAt: Column[Instant]                  = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]                  = column("updated_at", timestamptz.asInstant)
  override val deletedAt: Column[Option[Instant]] = column("deleted_at", timestamptz.asInstant.opt)

  override val foreignId: Column[UserId] = userId

  def mapping: (List[Column[?]], Codec[UserAuthRow]) =
    (id, userId, provider, providerUserId, credential, metadata, createdAt, updatedAt, deletedAt)
}

case class UserAuthRowFilter(
  id: SqlPredicate[UserAuthId] = p.any,
  userId: SqlPredicate[UserId] = p.any,
  provider: SqlPredicate[Provider] = p.any,
  providerUserId: SqlPredicate[ProviderUserId] = p.any,
  credential: SqlPredicate[Credential] = p.any,
  deletedAt: SqlPredicate[Instant] = p.any)
    extends SqlFilter {
  override def filterFragment: AppliedFragment = fromPredicatesAndSeparator(
    (
      id             -> UserAuthRowTable.id,
      userId         -> UserAuthRowTable.userId,
      provider       -> UserAuthRowTable.provider,
      providerUserId -> UserAuthRowTable.providerUserId,
      credential     -> UserAuthRowTable.credential,
      deletedAt      -> UserAuthRowTable.deletedAt
    ),
    SqlAnd
  )
}

class UserAuthRepository(using Clock[IO]) {
  def save(userAuth: UserAuth): DB[UserAuth] = {
    Clock[IO].realTimeInstant.flatMap { now =>
      val row = UserAuthRow(userAuth, now)
      repository.upsert(row).as(row.toUserAuth)
    }
  }

  def findForUpdate(provider: Provider, pid: ProviderUserId): DBInTransaction[Option[UserAuth]] = {
    val filter = UserAuthRowFilter(provider = p.equal(provider), providerUserId = p.equal(pid), deletedAt = p.isNull)
    repository.findOneByFilter(filter, Lock.ForUpdate).map(_.map(_.toUserAuth))
  }

  def updateMetadata(userAuthId: UserAuthId, metadata: Metadata): DB[Unit] = {
    repository
      .updateById(userAuthId, (row: UserAuthRow) => row.copy(metadata = metadata))
  }

  private val repository: IdRepository[UserAuthRow, UserAuthId] & SoftDeleteRepository[UserAuthRow, UserAuthId] & ForeignIdRepository[
    UserAuthRow,
    UserId
  ] & FilteringRepository[UserAuthRow, UserAuthRowFilter] = new IdRepository[UserAuthRow, UserAuthId](_.id)
    with SoftDeleteRepository[UserAuthRow, UserAuthId]
    with ForeignIdRepository[UserAuthRow, UserId]
    with FilteringRepository[UserAuthRow, UserAuthRowFilter] {
    override val table: UserAuthRowTable.type = UserAuthRowTable
  }
}
