package madrileno.utils.featureflag.domain

import io.circe.Json
import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type FlagId = UUID
object FlagId extends Opaque[FlagId, UUID]

opaque type FlagKey = String
object FlagKey extends Opaque[FlagKey, String] {
  private val Pattern = "^[a-z][a-z0-9_-]*$".r
  override def validate(value: String): Either[String, FlagKey] = {
    val trimmed = value.trim
    if (trimmed.isEmpty || trimmed.length > 128) Left("FlagKey must be 1-128 chars")
    else if (!Pattern.matches(trimmed)) Left("FlagKey must match [a-z][a-z0-9_-]*")
    else Right(trimmed)
  }
}

opaque type FlagDescription = String
object FlagDescription extends Opaque[FlagDescription, String] {
  override def validate(value: String): Either[String, FlagDescription] = {
    val trimmed = value.trim
    if (trimmed.length > 500) Left("FlagDescription must be at most 500 chars") else Right(trimmed)
  }
}

enum VariantType {
  case Boolean, String, Int, Json
}

enum FlagVariant {
  case BoolVariant(value: Boolean)  extends FlagVariant
  case StringVariant(value: String) extends FlagVariant
  case IntVariant(value: Int)       extends FlagVariant
  case JsonVariant(value: Json)     extends FlagVariant

  def variantType: VariantType = this match {
    case _: BoolVariant   => VariantType.Boolean
    case _: StringVariant => VariantType.String
    case _: IntVariant    => VariantType.Int
    case _: JsonVariant   => VariantType.Json
  }

  def toJson: Json = this match {
    case BoolVariant(value)   => Json.fromBoolean(value)
    case StringVariant(value) => Json.fromString(value)
    case IntVariant(value)    => Json.fromInt(value)
    case JsonVariant(value)   => value
  }
}

object FlagVariant {
  def fromJson(variantType: VariantType, json: Json): Either[String, FlagVariant] = variantType match {
    case VariantType.Boolean => json.asBoolean.toRight(s"expected boolean, got: ${json.noSpaces}").map(BoolVariant.apply)
    case VariantType.String  => json.asString.toRight(s"expected string, got: ${json.noSpaces}").map(StringVariant.apply)
    case VariantType.Int     => json.asNumber.flatMap(_.toInt).toRight(s"expected int, got: ${json.noSpaces}").map(IntVariant.apply)
    case VariantType.Json    => Right(JsonVariant(json))
  }
}

final case class FeatureFlag(
  id: FlagId,
  key: FlagKey,
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  createdAt: Instant,
  updatedAt: Instant)
