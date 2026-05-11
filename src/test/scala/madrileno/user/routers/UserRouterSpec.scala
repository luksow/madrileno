package madrileno.user.routers

import madrileno.auth.domain.AuthContext
import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData}
import madrileno.user.domain.User
import madrileno.user.routers.dto.UserDto
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.Instant

class UserRouterSpec extends BaseRouteSpec with TestApplicationLoader {

  override def route: Route = application.routes(wsb)

  private def seedUser(): User = {
    val user = TestData.user()
    val _ = application.transactor
      .inTransaction(application.userRepository.create(user, Instant.now()))
      .unsafeRunSync()
    user
  }

  path("/v1/users/me")(
    supports(
      GET,
      description = "Get the authenticated user's profile",
      summary = "Returns the current user (id, fullName, emailAddress, emailVerified, avatarUrl)",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Users")
    )(
      withSetup(seedUser())
        .request(user => onRequest(security = bearer.apply(validJwt(AuthContext(user)))))
        .respondsWith[UserDto](Ok, description = "The authenticated user")
        .assert { case (ctx, user) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe user.id
          response.body.fullName shouldBe user.fullName
          response.body.emailAddress shouldBe user.emailAddress
          response.body.emailVerified shouldBe user.emailVerified
        }
    )
  )
}
