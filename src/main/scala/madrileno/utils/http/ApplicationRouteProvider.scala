package madrileno.utils.http

import cats.effect.IO
import madrileno.auth.domain.AuthContext
import org.http4s.server.websocket.WebSocketBuilder2
import pl.iterators.stir.server.directives.RouteDirectives
import pl.iterators.stir.server.{Directives, Route, RouteConcatenation}

trait ApplicationRouteProvider extends RouteProvider with AuthRouteProvider with WsRouteProvider with AuthWsRouteProvider with Directives {
  override def route: Route                                                       = RouteDirectives.reject
  override def route(auth: AuthContext): Route                                    = RouteDirectives.reject
  override def wsRoutes(wsb: WebSocketBuilder2[IO]): Route                        = RouteDirectives.reject
  override def wsRoutes(wsb: WebSocketBuilder2[IO], auth: AuthContext): Route     = RouteDirectives.reject
}

trait RouteProvider extends RouteConcatenation {
  def route: Route
}

trait AuthRouteProvider extends RouteConcatenation {
  def route(auth: AuthContext): Route
}

trait WsRouteProvider extends RouteConcatenation {
  def wsRoutes(wsb: WebSocketBuilder2[IO]): Route
}

trait AuthWsRouteProvider extends RouteConcatenation {
  def wsRoutes(wsb: WebSocketBuilder2[IO], auth: AuthContext): Route
}
