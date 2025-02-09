package madrileno.utils.observability

import cats.effect.IO
import org.http4s.Headers
import org.http4s.otel4s.middleware.instances.all.*
import org.typelevel.otel4s.Attribute
import sttp.capabilities.{Effect, WebSockets}
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{GenericRequest, Response, WebSocketStreamBackend}

private class OpenTelemetryTracingBackend[P](delegate: WebSocketStreamBackend[IO, P])(using tc: TelemetryContext)
    extends DelegateBackend(delegate)
    with WebSocketStreamBackend[IO, P] {
  override def send[T](request: GenericRequest[T, P & WebSockets & Effect[IO]]): IO[Response[T]] = {
    tc.tracer.propagate(Headers.empty).flatMap { headers =>
      val newRequest        = request.headers(headers.headers.map(h => h.name.toString -> h.value).toMap)
      val requestAttributes = Seq(Attribute("http.request.method", request.method.method), Attribute("url.full", request.uri.toString))
      tc.tracer
        .span(s"HTTP Client ${request.method.method} ${request.uri.withPath(Seq.empty).toString}", requestAttributes)
        .use { span =>
          delegate.send(newRequest).flatMap { response =>
            span.addAttribute(Attribute("http.response.status_code", response.code.code.toLong)).as(response)
          }
        }
    }
  }
}

object OpenTelemetryTracingBackend {
  def apply[S](backend: WebSocketStreamBackend[IO, S])(using TelemetryContext): WebSocketStreamBackend[IO, S] =
    new OpenTelemetryTracingBackend[S](backend)
}
