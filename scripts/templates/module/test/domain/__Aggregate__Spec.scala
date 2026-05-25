package __package__.__aggregate__.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class __Aggregate__Spec extends AnyWordSpec with Matchers {

  private val now = Instant.parse("2026-01-01T10:00:00Z")
  private val id  = __Aggregate__Id(UUID.randomUUID())

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

  "__Aggregate__.create" should {
    "set createdAt = updatedAt = now and leave deletedAt empty" in {
      val entity = __Aggregate__.create(id, __Aggregate__Name("original"), now)
      entity.createdAt shouldBe now
      entity.updatedAt shouldBe now
      entity.deletedAt shouldBe None
    }
  }

  "__Aggregate__.rename" should {
    val entity = __Aggregate__.create(id, __Aggregate__Name("original"), now)

    "update name and bump updatedAt" in {
      val later = now.plusSeconds(60)
      entity.rename(__Aggregate__Name("updated"), later) shouldBe
        Right(entity.copy(name = __Aggregate__Name("updated"), updatedAt = later))
    }

    "reject when the name is unchanged" in {
      entity.rename(entity.name, now.plusSeconds(60)) shouldBe Left(RenameRejection.NameUnchanged)
    }
  }
}
