package madrileno.utils.http

import cats.effect.IO
import org.http4s.server.websocket.WebSocketBuilder2
import pl.iterators.stir.server.{Route, RouteConcatenation}

trait WebSocketRouteProvider extends RouteConcatenation {
  def wsRoutes(wsb: WebSocketBuilder2[IO]): Route
}
