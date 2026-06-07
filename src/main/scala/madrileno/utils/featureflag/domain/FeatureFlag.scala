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
  override def validate(value: String): Either[String, FlagKey] =
    if (value.isEmpty || value.length > 128) Left("FlagKey must be 1-128 chars")
    else if (!Pattern.matches(value)) Left("FlagKey must match [a-z][a-z0-9_-]*")
    else Right(value)
}

enum VariantType(val wireName: String) {
  case Boolean extends VariantType("boolean")
  case String  extends VariantType("string")
  case Int     extends VariantType("int")
  case Json    extends VariantType("json")
}

object VariantType {
  def fromWireName(s: String): Option[VariantType] = values.find(_.wireName == s)
}

enum FlagVariant {
  case BoolVariant(value: Boolean)  extends FlagVariant
  case StringVariant(value: String) extends FlagVariant
  case IntVariant(value: Int)       extends FlagVariant
  case JsonVariant(value: Json)     extends FlagVariant
}

object FlagVariant {
  def variantType(v: FlagVariant): VariantType = v match {
    case _: BoolVariant   => VariantType.Boolean
    case _: StringVariant => VariantType.String
    case _: IntVariant    => VariantType.Int
    case _: JsonVariant   => VariantType.Json
  }

  def fromJson(variantType: VariantType, json: Json): Either[String, FlagVariant] = variantType match {
    case VariantType.Boolean => json.asBoolean.toRight(s"expected boolean, got: ${json.noSpaces}").map(BoolVariant.apply)
    case VariantType.String  => json.asString.toRight(s"expected string, got: ${json.noSpaces}").map(StringVariant.apply)
    case VariantType.Int     => json.asNumber.flatMap(_.toInt).toRight(s"expected int, got: ${json.noSpaces}").map(IntVariant.apply)
    case VariantType.Json    => Right(JsonVariant(json))
  }

  def toJson(v: FlagVariant): Json = v match {
    case BoolVariant(value)   => Json.fromBoolean(value)
    case StringVariant(value) => Json.fromString(value)
    case IntVariant(value)    => Json.fromInt(value)
    case JsonVariant(value)   => value
  }
}

final case class FeatureFlag(
  id: FlagId,
  key: FlagKey,
  description: String,
  variantType: VariantType,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  createdAt: Instant,
  updatedAt: Instant)
