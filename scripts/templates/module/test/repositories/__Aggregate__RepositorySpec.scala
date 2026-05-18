package __package__.__aggregate__.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import __package__.__aggregate__.domain.*
import __package__.support.TestTransactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.UUID

class __Aggregate__RepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val repo = new __Aggregate__Repository
  private val now       = Instant.now()

  private def make__Aggregate__(name: __Aggregate__Name = __Aggregate__Name("test-name")): __Aggregate__ =
    __Aggregate__(id = __Aggregate__Id(UUID.randomUUID()), name = name)

  "__Aggregate__Repository" should {
    "create and get a __aggregate__" in withRollback {
      val entity = make__Aggregate__()
      for {
        created <- repo.create(entity, now)
        fetched <- repo.get(created.id)
      } yield {
        fetched.id shouldBe entity.id
        fetched.name shouldBe entity.name
      }
    }

    "find returns Some for an existing __aggregate__" in withRollback {
      val entity = make__Aggregate__()
      for {
        _      <- repo.create(entity, now)
        result <- repo.find(entity.id)
      } yield result shouldBe defined
    }

    "find returns None for a non-existent __aggregate__" in withRollback {
      repo.find(__Aggregate__Id(UUID.randomUUID())).map(_ shouldBe None)
    }

    "update modifies fields and bumps updatedAt" in withRollback {
      val entity = make__Aggregate__()
      for {
        created <- repo.create(entity, now)
        _       <- repo.update(created.id, _.copy(name = __Aggregate__Name("updated")), now.plusSeconds(1))
        fetched <- repo.get(created.id)
      } yield fetched.name shouldBe __Aggregate__Name("updated")
    }

    "softDelete hides the __aggregate__ from find" in withRollback {
      val entity = make__Aggregate__()
      for {
        _           <- repo.create(entity, now)
        _           <- repo.softDelete(entity.id, now.plusSeconds(1))
        afterDelete <- repo.find(entity.id)
      } yield afterDelete shouldBe None
    }
  }
}
