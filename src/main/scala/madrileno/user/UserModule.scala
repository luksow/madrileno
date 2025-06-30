package madrileno.user

import com.softwaremill.macwire.*
import madrileno.user.repositories.UserRowRepository

trait UserModule {
  lazy val userRowRepository: UserRowRepository = wire[UserRowRepository]
}
