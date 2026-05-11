package madrileno.user.services

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.repositories.UserRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class UserServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val userRepository = new UserRepository
  private lazy val service        = new UserService(userRepository, transactor)
  private val now                 = Instant.now()

  "UserService" should {
    "getCurrentUser returns the user for an existing id" in {
      val user = TestData.user()
      for {
        _       <- transactor.inSession(userRepository.create(user, now))
        fetched <- service.getCurrentUser(user.id)
      } yield {
        fetched.id shouldBe user.id
        fetched.fullName shouldBe user.fullName
        fetched.emailAddress shouldBe user.emailAddress
        fetched.emailVerified shouldBe user.emailVerified
      }
    }
  }
}
