package madrileno.user

import cats.effect.{Clock, IO}
import com.softwaremill.macwire.*
import madrileno.user.repositories.UserRepository

trait UserModule {
  val clock: Clock[IO]
  lazy val userRepository: UserRepository = wire[UserRepository]
}
