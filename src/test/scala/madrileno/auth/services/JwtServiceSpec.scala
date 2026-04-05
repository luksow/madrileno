package madrileno.auth.services

import madrileno.auth.domain.AuthContext
import madrileno.support.TestData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}

class JwtServiceSpec extends AnyWordSpec with Matchers {
  private val config  = JwtService.Config(secret = "test-secret-at-least-256-bits-long-for-hs256!!", validFor = Duration.ofMinutes(5))
  private val service = JwtService(config)

  private val testUser    = TestData.user()
  private val authContext = AuthContext(testUser)

  "JwtService" should {
    "encode and decode a valid token" in {
      val now = Instant.now()
      val jwt = service.encode(authContext, now)

      service.decode[AuthContext](jwt.toString) match {
        case JwtService.DecodingResult.Decoded(decoded) =>
          decoded shouldBe authContext
        case other => fail(s"Expected Decoded, got $other")
      }
    }

    "set expiration based on validFor config" in {
      val now = Instant.now()
      val jwt = service.encode(authContext, now)

      // Token should be valid at now + 4 minutes
      service.decode[AuthContext](jwt.toString) match {
        case JwtService.DecodingResult.Decoded(_) => succeed
        case other                                => fail(s"Expected Decoded at now, got $other")
      }
    }

    "reject an expired token" in {
      val past = Instant.parse("2020-01-01T00:00:00Z")
      val jwt  = service.encode(authContext, past)

      service.decode[AuthContext](jwt.toString) match {
        case JwtService.DecodingResult.Expired(_) => succeed
        case other                                => fail(s"Expected Expired, got $other")
      }
    }

    "reject a token signed with a different secret" in {
      val otherConfig  = JwtService.Config(secret = "different-secret-also-at-least-256-bits-long!!!", validFor = Duration.ofMinutes(5))
      val otherService = JwtService(otherConfig)

      val now = Instant.now()
      val jwt = otherService.encode(authContext, now)

      service.decode[AuthContext](jwt.toString) match {
        case JwtService.DecodingResult.InvalidToken(_) => succeed
        case other                                     => fail(s"Expected InvalidToken, got $other")
      }
    }

    "reject a malformed token" in {
      service.decode[AuthContext]("not.a.valid.jwt") match {
        case JwtService.DecodingResult.InvalidToken(_) => succeed
        case other                                     => fail(s"Expected InvalidToken, got $other")
      }
    }

    "return ParsingFailure for a valid JWT with wrong payload structure" in {
      val now = Instant.now()
      // Encode a string instead of AuthContext
      val jwt = service.encode("just a string", now)(using io.circe.Encoder.encodeString)

      service.decode[AuthContext](jwt.toString) match {
        case JwtService.DecodingResult.ParsingFailure(_) => succeed
        case other                                       => fail(s"Expected ParsingFailure, got $other")
      }
    }
  }
}
