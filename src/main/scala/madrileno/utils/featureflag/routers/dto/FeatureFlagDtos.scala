package madrileno.utils.featureflag.routers.dto

import io.circe.{Json, KeyDecoder, KeyEncoder}
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.services.{CreateFlagCommand, RuleData, UpdateFlagCommand}
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

private given Codec.AsObject[FlagVariant]   = Codec.AsObject.derived
private given Codec.AsObject[RuleCondition] = Codec.AsObject.derived
private given Codec.AsObject[RuleOutcome]   = Codec.AsObject.derived

private given KeyEncoder[FlagKey] = KeyEncoder.encodeKeyString.contramap(_.unwrap)
private given KeyDecoder[FlagKey] = KeyDecoder.instance(FlagKey.from(_).toOption)

final case class RuleDto(
  id: RuleId,
  position: RulePosition,
  description: FlagDescription,
  conditions: List[RuleCondition],
  outcome: RuleOutcome,
  createdAt: Instant)
    derives Encoder.AsObject,
      Decoder

object RuleDto {
  def apply(rule: Rule): RuleDto = {
    import io.scalaland.chimney.dsl.*
    rule.into[RuleDto].transform
  }
}

final case class FeatureFlagDto(
  id: FlagId,
  key: FlagKey,
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  rules: List[RuleDto],
  createdAt: Instant,
  updatedAt: Instant)
    derives Encoder.AsObject,
      Decoder

object FeatureFlagDto {
  def apply(flag: FeatureFlag): FeatureFlagDto = {
    import io.scalaland.chimney.dsl.*
    flag.into[FeatureFlagDto].withFieldConst(_.rules, flag.rules.map(RuleDto(_))).transform
  }
}

final case class RuleRequest(
  position: RulePosition,
  description: FlagDescription,
  conditions: List[RuleCondition],
  outcome: RuleOutcome)
    derives Encoder.AsObject,
      Decoder

object RuleRequest {
  extension (rules: List[RuleRequest]) {
    def toRuleData: List[RuleData] = rules.map(r => RuleData(r.position, r.description, r.conditions, r.outcome))
  }
}

final case class CreateFlagRequest(
  key: FlagKey,
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  rules: List[RuleRequest])
    derives Encoder.AsObject,
      Decoder

object CreateFlagRequest {
  import RuleRequest.toRuleData
  extension (request: CreateFlagRequest) {
    def toCommand(actor: Actor): CreateFlagCommand =
      CreateFlagCommand(
        request.key,
        request.description,
        request.enabled,
        request.defaultValue,
        request.clientExposed,
        request.rules.toRuleData,
        actor
      )
  }
}

final case class UpdateFlagRequest(
  description: FlagDescription,
  enabled: Boolean,
  defaultValue: FlagVariant,
  clientExposed: Boolean,
  rules: List[RuleRequest])
    derives Encoder.AsObject,
      Decoder

object UpdateFlagRequest {
  import RuleRequest.toRuleData
  extension (request: UpdateFlagRequest) {
    def toCommand(key: FlagKey, actor: Actor): UpdateFlagCommand =
      UpdateFlagCommand(key, request.description, request.enabled, request.defaultValue, request.clientExposed, request.rules.toRuleData, actor)
  }
}

final case class ToggleFlagRequest(enabled: Boolean) derives Encoder.AsObject, Decoder

final case class SegmentDto(
  id: SegmentId,
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition],
  createdAt: Instant,
  updatedAt: Instant)
    derives Encoder.AsObject,
      Decoder

object SegmentDto {
  def apply(segment: Segment): SegmentDto = {
    import io.scalaland.chimney.dsl.*
    segment.into[SegmentDto].transform
  }
}

final case class CreateSegmentRequest(
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition])
    derives Encoder.AsObject,
      Decoder

final case class UpdateSegmentRequest(description: FlagDescription, conditions: List[RuleCondition]) derives Encoder.AsObject, Decoder

final case class AuditEntryDto(
  id: AuditEntryId,
  flagId: Option[FlagId],
  flagKey: FlagKey,
  actor: Actor,
  action: AuditAction,
  before: Option[FeatureFlagDto],
  after: Option[FeatureFlagDto],
  createdAt: Instant)
    derives Encoder.AsObject,
      Decoder

object AuditEntryDto {
  def apply(entry: AuditEntry): AuditEntryDto = {
    import io.scalaland.chimney.dsl.*
    entry
      .into[AuditEntryDto]
      .withFieldConst(_.before, entry.before.map(FeatureFlagDto(_)))
      .withFieldConst(_.after, entry.after.map(FeatureFlagDto(_)))
      .transform
  }
}

final case class EvaluateFlagRequest(targetingKey: TargetingKey, attributes: Map[String, String]) derives Encoder.AsObject, Decoder

final case class EvaluationResultDto(value: Json, reason: EvaluationReason) derives Encoder.AsObject, Decoder

final case class ClientFlagsDto(flags: Map[FlagKey, Json]) derives Encoder.AsObject, Decoder
