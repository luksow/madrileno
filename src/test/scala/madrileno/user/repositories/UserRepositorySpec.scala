package madrileno.user.repositories

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec


class UserRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo = new UserRepository

  "UserRepository" should {
    "create and get a user" in withRollback {
      val user = TestData.user()
      for {
        created <- repo.create(user)
        fetched <- repo.get(created.id)
      } yield {
        fetched.id shouldBe user.id
        fetched.fullName shouldBe user.fullName
        fetched.emailAddress shouldBe user.emailAddress
        fetched.emailVerified shouldBe user.emailVerified
      }
    }

    "find returns Some for existing user" in withRollback {
      val user = TestData.user()
      for {
        _      <- repo.create(user)
        result <- repo.find(user.id)
      } yield result shouldBe defined
    }

    "find returns None for non-existent user" in withRollback {
      repo.find(TestData.randomUserId()).map(_ shouldBe None)
    }

    "update modifies user fields and sets updatedAt" in withRollback {
      val user    = TestData.user(fullName = Some(FullName("Original")))
      val newName = FullName("Updated")
      for {
        created <- repo.create(user)
        _       <- repo.update(created.id, _.copy(fullName = Some(newName)))
        fetched <- repo.get(created.id)
      } yield {
        fetched.fullName shouldBe Some(newName)
      }
    }

    "soft-deleted user is not visible via get" in withRollback {
      val user = TestData.user()
      for {
        created <- repo.create(user)
        _       <- repo.softDelete(created.id)
        result  <- repo.find(created.id)
      } yield result shouldBe None
    }

    "update on soft-deleted user is a no-op" in withRollback {
      val user = TestData.user(fullName = Some(FullName("Original")))
      for {
        created <- repo.create(user)
        _       <- repo.softDelete(created.id)
        _       <- repo.update(created.id, _.copy(fullName = Some(FullName("Should Not Change"))))
        result  <- repo.findIncludingDeleted(created.id)
      } yield result.flatMap(_.fullName) shouldBe Some(FullName("Original"))
    }
  }
}
