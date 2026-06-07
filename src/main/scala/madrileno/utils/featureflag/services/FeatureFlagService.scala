package madrileno.utils.featureflag.services

import cats.effect.IO
import io.circe.Json
import madrileno.utils.featureflag.domain.*

trait FeatureFlagService {
  def evaluator(ctx: EvaluationContext): FlagEvaluator

  final def evaluateBoolean(key: FlagKey, default: Boolean)(using ctx: EvaluationContext): IO[Boolean] = evaluator(ctx).boolean(key, default)
  final def evaluateString(key: FlagKey, default: String)(using ctx: EvaluationContext): IO[String]    = evaluator(ctx).string(key, default)
  final def evaluateInt(key: FlagKey, default: Int)(using ctx: EvaluationContext): IO[Int]             = evaluator(ctx).int(key, default)
  final def evaluateJson(key: FlagKey, default: Json)(using ctx: EvaluationContext): IO[Json]          = evaluator(ctx).json(key, default)

  final def evaluateBooleanDetail(key: FlagKey, default: Boolean)(using ctx: EvaluationContext): IO[EvaluationDetail[Boolean]] =
    evaluator(ctx).booleanDetail(key, default)
  final def evaluateStringDetail(key: FlagKey, default: String)(using ctx: EvaluationContext): IO[EvaluationDetail[String]] =
    evaluator(ctx).stringDetail(key, default)
  final def evaluateIntDetail(key: FlagKey, default: Int)(using ctx: EvaluationContext): IO[EvaluationDetail[Int]] =
    evaluator(ctx).intDetail(key, default)
  final def evaluateJsonDetail(key: FlagKey, default: Json)(using ctx: EvaluationContext): IO[EvaluationDetail[Json]] =
    evaluator(ctx).jsonDetail(key, default)
}

trait FlagEvaluator {
  def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]]
  def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]]
  def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]]
  def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]]

  final def boolean(key: FlagKey, default: Boolean): IO[Boolean] = booleanDetail(key, default).map(_.value)
  final def string(key: FlagKey, default: String): IO[String]    = stringDetail(key, default).map(_.value)
  final def int(key: FlagKey, default: Int): IO[Int]             = intDetail(key, default).map(_.value)
  final def json(key: FlagKey, default: Json): IO[Json]          = jsonDetail(key, default).map(_.value)
}
