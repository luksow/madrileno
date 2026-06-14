package madrileno.utils.featureflag.repositories

import cats.effect.IO
import io.circe.Json
import madrileno.utils.db.dsl.*
import madrileno.utils.db.transactor.DB
import madrileno.utils.featureflag.domain.*
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*

import java.time.Instant

private[repositories] final case class FeatureFlagRow(
  id: FlagId,
  key: FlagKey,
  description: FlagDescription,
  variantType: VariantType,
  enabled: Boolean,
  defaultValue: Json,
  clientExposed: Boolean,
  createdAt: Instant,
  updatedAt: Instant) {
  def toFeatureFlag: Either[String, FeatureFlag] = {
    import io.scalaland.chimney.dsl.*
    FlagVariant.fromJson(variantType, defaultValue).map { variant =>
      this.into[FeatureFlag].withFieldConst(_.defaultValue, variant).transform
    }
  }
}

private[repositories] object FeatureFlagRow {
  def apply(flag: FeatureFlag): FeatureFlagRow = {
    import io.scalaland.chimney.dsl.*
    flag
      .into[FeatureFlagRow]
      .withFieldConst(_.defaultValue, flag.defaultValue.toJson)
      .withFieldConst(_.variantType, flag.defaultValue.variantType)
      .transform
  }
}

private[repositories] object FeatureFlagRowTable extends Table[FeatureFlagRow]("feature_flag") with IdTable[FeatureFlagRow, FlagId] {
  override val id: Column[FlagId]          = column("id", uuid.as[FlagId])
  val key: Column[FlagKey]                 = column("key", text.as[FlagKey])
  val description: Column[FlagDescription] = column("description", text.as[FlagDescription])
  val variantType: Column[VariantType]     = column("variant_type", text.asEnum[VariantType])
  val enabled: Column[Boolean]             = column("enabled", bool)
  val defaultValue: Column[Json]           = column("default_value", jsonb)
  val clientExposed: Column[Boolean]       = column("client_exposed", bool)
  val createdAt: Column[Instant]           = column("created_at", timestamptz.asInstant)
  val updatedAt: Column[Instant]           = column("updated_at", timestamptz.asInstant)

  def mapping: (List[Column[?]], Codec[FeatureFlagRow]) =
    (id, key, description, variantType, enabled, defaultValue, clientExposed, createdAt, updatedAt)
}

private[repositories] final case class FeatureFlagRowFilter(id: SqlPredicate[FlagId] = p.any, key: SqlPredicate[FlagKey] = p.any) extends SqlFilter {
  override def filterFragment: AppliedFragment =
    SqlFilterDerivation.filterFragment(this, (FeatureFlagRowTable.id, FeatureFlagRowTable.key))
}

class FeatureFlagRepository {
  def findByKey(key: FlagKey): DB[Option[FeatureFlag]] =
    repository.findOneByFilter(FeatureFlagRowFilter(key = p.equal(key))).flatMap {
      case None => IO.pure(None)
      case Some(row) =>
        row.toFeatureFlag match {
          case Right(flag) => IO.pure(Some(flag))
          case Left(err)   => IO.raiseError(new IllegalStateException(s"Invalid feature_flag row for key=$key: $err"))
        }
    }

  def save(flag: FeatureFlag): DB[Unit] =
    repository.create(FeatureFlagRow(flag)).void

  private val repository: IdRepository[FeatureFlagRow, FlagId] & FilteringRepository[FeatureFlagRow, FeatureFlagRowFilter] =
    new IdRepository[FeatureFlagRow, FlagId](_.id) with FilteringRepository[FeatureFlagRow, FeatureFlagRowFilter] {
      override val table: FeatureFlagRowTable.type = FeatureFlagRowTable
    }
}
