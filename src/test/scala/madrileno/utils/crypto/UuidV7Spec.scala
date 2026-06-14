package madrileno.utils.crypto

import cats.effect.std.UUIDGen
import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO}
import madrileno.support.TestGivens
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.UUID

class UuidV7Spec extends AnyWordSpec with Matchers {

  "UuidV7.fromParts" should {
    "set the version nibble to 7" in {
      UuidV7.fromParts(0L, new UUID(0L, 0L)).version() shouldBe 7
    }

    "set the IETF variant" in {
      UuidV7.fromParts(0L, new UUID(0L, 0L)).variant() shouldBe 2
    }

    "encode the millisecond timestamp in the high 48 bits" in {
      val millis = 0x0123456789abL
      val uuid   = UuidV7.fromParts(millis, new UUID(0L, 0L))
      (uuid.getMostSignificantBits >>> 16) shouldBe millis
    }

    "preserve the random bits outside the version and variant fields" in {
      val random = new UUID(-1L, -1L) // all bits set
      val uuid   = UuidV7.fromParts(0L, random)
      (uuid.getMostSignificantBits & 0x0fffL) shouldBe 0x0fffL // rand_a
      (uuid.getLeastSignificantBits & 0x3fffffffffffffffL) shouldBe 0x3fffffffffffffffL // rand_b
    }

    "order by timestamp ahead of the random bits" in {
      val earlierMaxRandom = UuidV7.fromParts(1000L, new UUID(-1L, -1L))
      val laterMinRandom   = UuidV7.fromParts(2000L, new UUID(0L, 0L))
      java.lang.Long.compareUnsigned(earlierMaxRandom.getMostSignificantBits, laterMinRandom.getMostSignificantBits) should be < 0
    }
  }

  "UuidV7.generate" should {
    "build a v7 UUID from the injected clock and UUIDGen" in {
      given Clock[IO]   = TestGivens.fixedClock(Instant.ofEpochMilli(1234567890L))
      given UUIDGen[IO] = TestGivens.deterministicUUIDs()
      val uuid          = UuidV7.generate.unsafeRunSync()
      uuid.version() shouldBe 7
      (uuid.getMostSignificantBits >>> 16) shouldBe 1234567890L
    }
  }
}
