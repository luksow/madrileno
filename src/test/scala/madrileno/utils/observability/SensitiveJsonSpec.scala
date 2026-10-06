package madrileno.utils.observability

import io.circe.{Json, parser}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SensitiveJsonSpec extends AnyFunSpec with Matchers {

  private def json(document: String): Json = parser.parse(document).fold(failure => fail(failure.message), identity)

  describe("Fingerprint") {
    it("is stable, short, and does not contain the secret") {
      val secret      = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"
      val fingerprint = Fingerprint(secret).value
      fingerprint shouldBe Fingerprint(secret).value
      fingerprint should fullyMatch regex "fp:[0-9a-f]{8}"
      secret should not include fingerprint.drop(3)
    }

    it("differs between secrets") {
      Fingerprint("a").value should not be Fingerprint("b").value
    }
  }

  describe("SensitiveJson.redact") {
    it("replaces sensitive string values by fingerprints and keeps the rest") {
      val refreshToken = "0192f5d4-2b3a-7c1e-9a0b-6f1e2d3c4b5a"
      val redacted     = SensitiveJson.redact(json(s"""{"refreshToken": "$refreshToken", "userCreated": true}"""))
      redacted shouldBe json(s"""{"refreshToken": "${Fingerprint(refreshToken).value}", "userCreated": true}""")
    }

    it("reaches into nested objects and arrays") {
      val redacted = SensitiveJson.redact(json("""{"data": [{"jwt": "secret-1"}, {"nested": {"password": "secret-2"}}], "n": 1}"""))
      redacted.noSpaces should not include "secret-1"
      redacted.noSpaces should not include "secret-2"
      redacted.hcursor.downField("n").as[Int] shouldBe Right(1)
    }

    it("matches field names regardless of case and separators") {
      List("refreshToken", "refresh_token", "Refresh-Token", "REFRESHTOKEN").foreach { name =>
        SensitiveJson.isSensitiveField(name) shouldBe true
      }
      SensitiveJson.isSensitiveField("userCreated") shouldBe false
    }

    it("fingerprints non-string secrets and leaves null alone") {
      val redacted = SensitiveJson.redact(json("""{"token": 12345, "secret": null}"""))
      redacted.hcursor.downField("token").as[String].map(_.take(3)) shouldBe Right("fp:")
      redacted.hcursor.downField("secret").focus shouldBe Some(Json.Null)
    }

    it("is the identity on documents without sensitive fields") {
      val document = json("""{"id": "abc", "items": [1, 2, {"name": "x"}]}""")
      SensitiveJson.redact(document) shouldBe document
    }
  }
}
