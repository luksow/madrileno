package __package__.__aggregate__

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import __package__.auth.domain.AuthContext
import __package__.__aggregate__.repositories.__Aggregate__Repository
import __package__.__aggregate__.routers.__Aggregate__Router
import __package__.__aggregate__.services.__Aggregate__Service
import __package__.utils.db.transactor.Transactor
import __package__.utils.http.AuthRouteProvider
import pl.iterators.stir.server.Route

trait __Aggregate__Module extends AuthRouteProvider {
  val clock: Clock[IO]
  val transactor: Transactor
  lazy val __aggregate__Repository: __Aggregate__Repository = wire[__Aggregate__Repository]

  private val __aggregate__Service = wire[__Aggregate__Service]
  private val __aggregate__Router  = wire[__Aggregate__Router]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ __aggregate__Router.authedRoutes(auth)
  }
}
