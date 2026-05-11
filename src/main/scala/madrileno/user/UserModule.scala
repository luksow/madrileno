package madrileno.user

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.auth.domain.AuthContext
import madrileno.user.repositories.UserRepository
import madrileno.user.routers.UserRouter
import madrileno.user.services.UserService
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.http.AuthRouteProvider
import pl.iterators.stir.server.Route

trait UserModule extends AuthRouteProvider {
  val clock: Clock[IO]
  val transactor: Transactor
  lazy val userRepository: UserRepository = wire[UserRepository]

  private val userService = wire[UserService]
  private val userRouter  = wire[UserRouter]

  override abstract def route(auth: AuthContext): Route = {
    super.route(auth) ~ userRouter.authedRoutes(auth)
  }
}
