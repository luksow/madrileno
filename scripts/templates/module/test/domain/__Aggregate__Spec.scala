package __package__.__aggregate__.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class __Aggregate__Spec extends AnyWordSpec with Matchers {

  "__Aggregate__Name" should {
    "accept non-empty strings" in {
      __Aggregate__Name("hello").toString shouldBe "hello"
    }

    "trim whitespace" in {
      __Aggregate__Name("  hello  ").toString shouldBe "hello"
    }

    "reject empty strings" in {
      assertThrows[IllegalArgumentException](__Aggregate__Name(""))
    }

    "reject whitespace-only strings" in {
      assertThrows[IllegalArgumentException](__Aggregate__Name("   "))
    }
  }

  "__Aggregate__" should {
    val entity = __Aggregate__(id = __Aggregate__Id(UUID.randomUUID()), name = __Aggregate__Name("original"))

    "rename to a new name" in {
      entity.rename(__Aggregate__Name("updated")) shouldBe Right(entity.copy(name = __Aggregate__Name("updated")))
    }

    "reject rename to the same name" in {
      entity.rename(entity.name) shouldBe Left(RenameRejection.NameUnchanged)
    }
  }
}
