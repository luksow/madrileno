package madrileno.utils.featureflag.domain

import io.circe.Json

enum FlagVariant {
  case BoolVariant(value: Boolean)  extends FlagVariant
  case StringVariant(value: String) extends FlagVariant
  case IntVariant(value: Int)       extends FlagVariant
  case JsonVariant(value: Json)     extends FlagVariant
}
