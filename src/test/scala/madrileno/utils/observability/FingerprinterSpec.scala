package madrileno.utils.observability

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pl.iterators.kebs.opaque.Opaque

opaque type SpecToken = String
object SpecToken extends Opaque[SpecToken, String]

class FingerprinterSpec extends AnyWordSpec with Matchers {
  private val fingerprinter = new Fingerprinter(Fingerprinter.Config("spec-secret"))
  private val secret        = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"

  "Fingerprinter" should {
    "produce a short, stable handle that reveals nothing of the secret" in {
      val fingerprint = fingerprinter(secret).value
      fingerprint shouldBe fingerprinter(secret).value
      fingerprint should fullyMatch regex "fp:[0-9a-f]{8}"
      secret should not include fingerprint.drop(3)
    }

    "differ between secrets" in {
      fingerprinter("a").value should not be fingerprinter("b").value
    }

    "differ between keys, so a fingerprint cannot be checked offline against a guess" in {
      new Fingerprinter(Fingerprinter.Config("another-secret"))("password123").value should not be fingerprinter("password123").value
    }

    "accept an opaque type over String without unwrapping and fingerprint it as the underlying string" in {
      fingerprinter(SpecToken(secret)).value shouldBe fingerprinter(secret).value
    }

    "print as its value when interpolated" in {
      s"${fingerprinter(secret)}" shouldBe fingerprinter(secret).value
    }
  }
}
