package madrileno.utils.http

import cats.effect.IO
import madrileno.utils.json.JsonProtocol
import madrileno.utils.json.JsonProtocol.*
import madrileno.utils.observability.TelemetryContext
import org.http4s.Status
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Directives

import java.net.URI

trait BaseRouter extends JsonProtocol with Directives {
  export Status.*

  def error[E: Encoder](
    status: Status,
    typeTag: String,
    title: String,
    detail: Option[String] = None,
    extension: E = ()
  )(using tc: TelemetryContext
  ): IO[ToResponseMarshallable] = {
    tc.tracer.currentSpanContext.map { spanContext =>
      val error =
        Error(
          Some(new URI(s"result:$typeTag")),
          Some(status),
          Some(title),
          detail,
          spanContext.map(c => new URI(s"trace-id:${c.traceIdHex}")),
          extension
        )
      status -> summon[Encoder[Error[E]]](error)
    }
  }

}
