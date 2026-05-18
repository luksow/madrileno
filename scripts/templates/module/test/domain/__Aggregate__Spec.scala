package __package__.__aggregate__.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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
}
