package madrileno.support

import cats.effect.IO
import io.circe.Json
import madrileno.utils.featureflag.domain.*
import madrileno.utils.featureflag.services.{FeatureFlagService, FlagEvaluator}

class TestFeatureFlagService private (values: Map[FlagKey, FlagVariant]) extends FeatureFlagService {

  def withFlag(key: FlagKey, value: FlagVariant): TestFeatureFlagService    = new TestFeatureFlagService(values + (key -> value))
  def withFlags(overrides: (FlagKey, FlagVariant)*): TestFeatureFlagService = new TestFeatureFlagService(values ++ overrides.toMap)

  override def evaluator(ctx: EvaluationContext): FlagEvaluator = new FlagEvaluator {
    private def lookup[T](
      key: FlagKey,
      default: T,
      extract: FlagVariant => Option[T]
    ): IO[EvaluationDetail[T]] =
      IO.pure(values.get(key) match {
        case None => EvaluationDetail(default, EvaluationReason.FlagNotFound)
        case Some(value) =>
          extract(value) match {
            case Some(v) => EvaluationDetail(v, EvaluationReason.Fallthrough)
            case None    => EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"stubbed flag $key has incompatible variant"))
          }
      })

    override def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]] =
      lookup(key, default, { case FlagVariant.BoolVariant(v) => Some(v); case _ => None })
    override def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]] =
      lookup(key, default, { case FlagVariant.StringVariant(v) => Some(v); case _ => None })
    override def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]] =
      lookup(key, default, { case FlagVariant.IntVariant(v) => Some(v); case _ => None })
    override def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]] =
      lookup(
        key,
        default,
        {
          case FlagVariant.JsonVariant(v)   => Some(v)
          case FlagVariant.BoolVariant(v)   => Some(Json.fromBoolean(v))
          case FlagVariant.StringVariant(v) => Some(Json.fromString(v))
          case FlagVariant.IntVariant(v)    => Some(Json.fromInt(v))
        }
      )
  }
}

object TestFeatureFlagService {
  def empty: TestFeatureFlagService                                      = new TestFeatureFlagService(Map.empty)
  def apply(values: (FlagKey, FlagVariant)*): TestFeatureFlagService     = new TestFeatureFlagService(values.toMap)
  def withMap(values: Map[FlagKey, FlagVariant]): TestFeatureFlagService = new TestFeatureFlagService(values)
}
