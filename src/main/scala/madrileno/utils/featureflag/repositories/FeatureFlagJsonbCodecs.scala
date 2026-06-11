package madrileno.utils.featureflag.repositories

import io.circe.{Codec, Decoder, Encoder}
import madrileno.utils.featureflag.domain.*

private[repositories] object FeatureFlagJsonbCodecs {
  given Codec[AttributeName] = Codec.from(Decoder.decodeString.emap(AttributeName.from), Encoder.encodeString.contramap(_.unwrap))
  given Codec[SegmentName]   = Codec.from(Decoder.decodeString.emap(SegmentName.from), Encoder.encodeString.contramap(_.unwrap))
  given Codec[Percentage]    = Codec.from(Decoder.decodeInt.emap(Percentage.from), Encoder.encodeInt.contramap(_.unwrap))
  given Codec[RolloutSeed]   = Codec.from(Decoder.decodeString.emap(RolloutSeed.from), Encoder.encodeString.contramap(_.unwrap))

  given Codec.AsObject[FlagVariant]   = Codec.AsObject.derived
  given Codec.AsObject[RuleCondition] = Codec.AsObject.derived
  given Codec.AsObject[RuleOutcome]   = Codec.AsObject.derived
}
