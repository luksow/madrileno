package madrileno.utils.http

import madrileno.auth.domain.AuthContext
import pl.iterators.stir.server.directives.RouteDirectives
import pl.iterators.stir.server.{Directives, Route, RouteConcatenation}

trait ApplicationRouteProvider extends RouteProvider with AuthRouteProvider with Directives {
  override def route: Route = RouteDirectives.reject

  override def route(auth: AuthContext): Route = RouteDirectives.reject
}

trait RouteProvider extends RouteConcatenation {
  def route: Route
}

trait AuthRouteProvider extends RouteConcatenation {
  def route(auth: AuthContext): Route
}
