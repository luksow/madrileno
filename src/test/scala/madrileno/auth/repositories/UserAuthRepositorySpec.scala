package madrileno.auth.repositories

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auth.domain.*
import madrileno.support.{TestData, TestGivens, TestTransactor}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class UserAuthRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private val testClock       = TestGivens.fixedClock()
  given cats.effect.Clock[IO] = testClock

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
        _      <- userRepo.create(user)
        first  <- repo.save(auth)
        second <- repo.save(auth.copy(metadata = updatedMetadata))
      } yield {
        first.id shouldBe second.id
        first.providerUserId shouldBe second.providerUserId
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

    "findForUpdate returns records for distinct provider user IDs" in withRollback {
      val (user, auth1, _) = createUserAuth()
      val token2           = TestData.verifiedExternalToken()
      val auth2            = UserAuth(TestData.randomUserAuthId(), user.id, token2)
      for {
        _      <- userRepo.create(user)
        _      <- repo.save(auth1)
        _      <- repo.save(auth2)
        found1 <- repo.findForUpdate(auth1.provider, auth1.providerUserId)
        found2 <- repo.findForUpdate(auth2.provider, auth2.providerUserId)
      } yield {
        found1.get.id shouldBe auth1.id
        found2.get.id shouldBe auth2.id
      }
    }
  }
}
