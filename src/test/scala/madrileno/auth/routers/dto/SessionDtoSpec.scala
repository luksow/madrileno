package madrileno.auth.routers.dto

import io.circe.syntax.*
import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SessionDtoSpec extends AnyWordSpec with Matchers {
  "SessionDto" should {
    "expose only the public session fields, never the secret hash or the row id" in {
      val token = TestData.refreshToken()
      val dto   = SessionDto(token)
      val json  = dto.asJson

      dto.id shouldBe token.familyId
      json.asObject.map(_.keys.toSet) shouldBe Some(Set("id", "userAgent", "ipAddress", "createdAt", "refreshedAt", "expiresAt"))
      json.noSpaces should not include token.secretHash.toString
      json.noSpaces should not include token.id.toString
    }
  }
}
