package madrileno.utils.observability

import cats.effect.IO
import io.opentelemetry.api.OpenTelemetry
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

case class TelemetryContext(
  meter: Meter[IO],
  tracer: Tracer[IO],
  underlying: OpenTelemetry)
