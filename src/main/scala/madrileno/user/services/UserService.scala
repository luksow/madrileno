package madrileno.user.services

import cats.effect.IO
import madrileno.user.domain.{User, UserId}
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.Transactor

class UserService(userRepository: UserRepository, transactor: Transactor) {
  def getCurrentUser(userId: UserId): IO[User] =
    transactor.inSession(userRepository.get(userId))
}
