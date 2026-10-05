package madrileno.auth.routers.dto

import io.circe.syntax.*
import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RefreshTokenDtoSpec extends AnyWordSpec with Matchers {
  "RefreshTokenDto" should {
    "expose only the public session fields, never the secret hash or family" in {
      val token = TestData.refreshToken()
      val json  = RefreshTokenDto(token).asJson

      json.asObject.map(_.keys.toSet) shouldBe Some(Set("id", "userAgent", "ipAddress", "createdAt", "expiresAt"))
      json.noSpaces should not include token.secretHash.toString
      json.noSpaces should not include token.familyId.toString
    }
  }
}
