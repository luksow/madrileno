package madrileno.auth.domain

import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthContextSpec extends AnyWordSpec with Matchers {

  private val uuid = "b2a88211-6bcd-4bb7-9b32-f0e93cc3cc05"

  "AuthContext decoder" should {
    "default emailVerified to false when the claim is absent (tokens issued before the field existed)" in {
      val json = parse(s"""{"userId":"$uuid","fullName":null,"avatarUrl":null}""").toOption.get
      AuthContext.from(json).map(_.emailVerified) shouldBe Right(false)
    }

    "read emailVerified when present" in {
      val json = parse(s"""{"userId":"$uuid","fullName":null,"avatarUrl":null,"emailVerified":true}""").toOption.get
      AuthContext.from(json).map(_.emailVerified) shouldBe Right(true)
    }
  }
}
