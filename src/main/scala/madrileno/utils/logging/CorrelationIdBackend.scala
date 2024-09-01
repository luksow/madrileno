package madrileno.utils.logging

import cats.effect.IO
import sttp.capabilities.{Effect, WebSockets}
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{GenericRequest, Response, WebSocketStreamBackend}

class CorrelationIdBackend[P](delegate: WebSocketStreamBackend[IO, P], headerName: String)
    extends DelegateBackend(delegate)
    with WebSocketStreamBackend[IO, P] {
  override def send[T](request: GenericRequest[T, P & WebSockets & Effect[IO]]): IO[Response[T]] =
    TracingContext.get.flatMap {
      case Some(ctx) => delegate.send(request.header(headerName, ctx.correlationId))
      case _         => delegate.send(request)
    }
}

object CorrelationIdBackend {
  def apply[S](backend: WebSocketStreamBackend[IO, S], headerName: String): WebSocketStreamBackend[IO, S] =
    new CorrelationIdBackend[S](backend, headerName)
}
