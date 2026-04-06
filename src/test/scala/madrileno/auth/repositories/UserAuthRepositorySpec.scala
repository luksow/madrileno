package madrileno.auth.repositories

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auth.domain.*
import madrileno.support.{TestData, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class UserAuthRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo     = new UserAuthRepository
  private lazy val userRepo = new madrileno.user.repositories.UserRepository

  private def createUserAuth() = {
    val user  = TestData.user()
    val token = TestData.verifiedExternalToken()
    val auth  = UserAuth(TestData.randomUserAuthId(), user.id, token)
    (user, auth, token)
  }

  "UserAuthRepository" should {
    "save and find a user auth record" in withRollback {
      val (user, auth, _) = createUserAuth()
      for {
        _     <- userRepo.create(user)
        saved <- repo.save(auth)
        found <- repo.findForUpdate(auth.provider, auth.providerUserId)
      } yield {
        saved.id shouldBe auth.id
        saved.userId shouldBe user.id
        saved.provider shouldBe auth.provider
        saved.providerUserId shouldBe auth.providerUserId
        found shouldBe defined
        found.get.id shouldBe auth.id
      }
    }

    "findForUpdate returns None for non-existent provider/pid" in withRollback {
      repo.findForUpdate(Provider.Firebase, ProviderUserId("nonexistent")).map(_ shouldBe None)
    }

    "upsert updates existing record on save" in withRollback {
      val (user, auth, _) = createUserAuth()
      val updatedMetadata = Metadata(io.circe.Json.obj("updated" -> io.circe.Json.fromBoolean(true)))
      for {
        _     <- userRepo.create(user)
        _     <- repo.save(auth)
        _     <- repo.save(auth.copy(metadata = updatedMetadata))
        found <- repo.findForUpdate(auth.provider, auth.providerUserId)
      } yield {
        found shouldBe defined
        found.get.id shouldBe auth.id
        found.get.metadata shouldBe updatedMetadata
      }
    }

    "updateMetadata changes only the metadata field" in withRollback {
      val (user, auth, _) = createUserAuth()
      val newMetadata     = Metadata(io.circe.Json.obj("key" -> io.circe.Json.fromString("value")))
      for {
        _     <- userRepo.create(user)
        _     <- repo.save(auth)
        _     <- repo.updateMetadata(auth.id, newMetadata)
        found <- repo.findForUpdate(auth.provider, auth.providerUserId)
      } yield {
        found shouldBe defined
        found.get.metadata shouldBe newMetadata
        found.get.providerUserId shouldBe auth.providerUserId
      }
    }

    "findForUpdate returns None for soft-deleted records" in withRollback {
      val (user, auth, _) = createUserAuth()
      for {
        _     <- userRepo.create(user)
        _     <- repo.save(auth)
        found <- repo.findForUpdate(auth.provider, auth.providerUserId)
        _ = found shouldBe defined
        _     <- repo.softDelete(auth.id)
        after <- repo.findForUpdate(auth.provider, auth.providerUserId)
      } yield after shouldBe None
    }
  }
}
