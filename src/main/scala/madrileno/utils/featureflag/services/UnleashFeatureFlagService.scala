package madrileno.utils.featureflag.services

import cats.effect.{IO, Resource}
import io.circe.{Json, parser}
import io.getunleash.util.UnleashConfig as UnleashSdkConfig
import io.getunleash.{DefaultUnleash, Unleash, UnleashContext}
import madrileno.utils.featureflag.UnleashSettings
import madrileno.utils.featureflag.domain.*
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

class UnleashFeatureFlagService(unleash: Unleash)(using TelemetryContext) extends FeatureFlagService with LoggingSupport {

  override def evaluator(ctx: EvaluationContext): FlagEvaluator = new FlagEvaluator {

    private def unleashContext: UnleashContext = {
      val builder      = UnleashContext.builder().userId(ctx.targetingKey.unwrap)
      val propsApplied = ctx.attributes.foldLeft(builder) { case (b, (k, v)) => b.addProperty(k, v) }
      propsApplied.build()
    }

    override def booleanDetail(key: FlagKey, default: Boolean): IO[EvaluationDetail[Boolean]] =
      IO.blocking {
        val enabled = unleash.isEnabled(key.unwrap, unleashContext, default)
        EvaluationDetail(enabled, EvaluationReason.RuleMatch)
      }.handleErrorWith(t =>
        logger.warn(t)(s"unleash isEnabled failed for $key, using default").as(EvaluationDetail(default, EvaluationReason.Error, Some(t.getMessage)))
      )

    override def stringDetail(key: FlagKey, default: String): IO[EvaluationDetail[String]] =
      payload(key)
        .map {
          case Left(reason)             => EvaluationDetail(default, reason)
          case Right(("string", value)) => EvaluationDetail(value, EvaluationReason.RuleMatch)
          case Right((other, _)) =>
            EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"unleash variant payload type=$other; expected string"))
        }
        .handleErrorWith(t =>
          logger
            .warn(t)(s"unleash getVariant failed for $key, using default")
            .as(EvaluationDetail(default, EvaluationReason.Error, Some(t.getMessage)))
        )

    override def intDetail(key: FlagKey, default: Int): IO[EvaluationDetail[Int]] =
      payload(key)
        .map {
          case Left(reason) => EvaluationDetail(default, reason)
          case Right(("number", value)) =>
            value.toIntOption match {
              case Some(i) => EvaluationDetail(i, EvaluationReason.RuleMatch)
              case None =>
                EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"unleash variant payload value=$value not parseable as int"))
            }
          case Right((other, _)) =>
            EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"unleash variant payload type=$other; expected number"))
        }
        .handleErrorWith(t =>
          logger
            .warn(t)(s"unleash getVariant failed for $key, using default")
            .as(EvaluationDetail(default, EvaluationReason.Error, Some(t.getMessage)))
        )

    override def jsonDetail(key: FlagKey, default: Json): IO[EvaluationDetail[Json]] =
      payload(key)
        .map {
          case Left(reason) => EvaluationDetail(default, reason)
          case Right(("json", value)) =>
            parser.parse(value) match {
              case Right(j) => EvaluationDetail(j, EvaluationReason.RuleMatch)
              case Left(e) =>
                EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"unleash variant payload not valid JSON: ${e.message}"))
            }
          case Right((other, _)) =>
            EvaluationDetail(default, EvaluationReason.VariantTypeMismatch, Some(s"unleash variant payload type=$other; expected json"))
        }
        .handleErrorWith(t =>
          logger
            .warn(t)(s"unleash getVariant failed for $key, using default")
            .as(EvaluationDetail(default, EvaluationReason.Error, Some(t.getMessage)))
        )

    private def payload(key: FlagKey): IO[Either[EvaluationReason, (String, String)]] =
      IO.blocking {
        val variant = unleash.getVariant(key.unwrap, unleashContext)
        if (!variant.isEnabled) Left(EvaluationReason.Fallthrough)
        else {
          val p = variant.getPayload
          if (p.isPresent) Right(p.get.getType -> p.get.getValue)
          else Left(EvaluationReason.VariantTypeMismatch)
        }
      }
  }
}

object UnleashFeatureFlagService {

  def resource(settings: UnleashSettings)(using TelemetryContext): Resource[IO, FeatureFlagService] =
    Resource
      .make(IO.blocking {
        val sdkConfig = UnleashSdkConfig
          .builder()
          .appName(settings.appName)
          .environment(settings.environment)
          .unleashAPI(settings.url)
          .apiKey(settings.apiToken)
          .build()
        new DefaultUnleash(sdkConfig)
      })(client => IO.blocking(client.shutdown()).handleError(_ => ()))
      .map(client => new UnleashFeatureFlagService(client))
}
