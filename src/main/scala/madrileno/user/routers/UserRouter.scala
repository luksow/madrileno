package madrileno.user.routers

import madrileno.auth.domain.AuthContext
import madrileno.user.routers.dto.UserDto
import madrileno.user.services.UserService
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

class UserRouter(userService: UserService) extends BaseRouter {
  def authedRoutes(authContext: AuthContext): Route = {
    (get & path("users" / "me") & pathEndOrSingleSlash) {
      complete {
        userService.getCurrentUser(authContext.userId).map[ToResponseMarshallable](user => Ok -> UserDto(user))
      }
    }
  }
}
