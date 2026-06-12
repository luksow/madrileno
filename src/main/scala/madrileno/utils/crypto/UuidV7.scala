package madrileno.utils.crypto

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}

import java.util.UUID

/** Generates time-ordered UUIDv7 (RFC 9562): a 48-bit Unix-millisecond timestamp in the high bits, the version/variant nibbles, and the remaining 74
  * bits random. Leading the value with the timestamp gives primary-key index locality that random v4 lacks. Millisecond resolution means IDs minted
  * within the same millisecond are not ordered relative to each other — fine for index locality, not a sortable-sequence guarantee.
  */
object UuidV7 {

  /** Builds a v7 UUID from a millisecond timestamp and a source of random bits (a v4 UUID). Kept pure so the bit layout can be tested without
    * effects.
    */
  def fromParts(epochMilli: Long, random: UUID): UUID = {
    val msb = (epochMilli << 16) | 0x7000L | (random.getMostSignificantBits & 0x0fffL) // ts (48) | version=7 | rand_a (12)
    val lsb = (random.getLeastSignificantBits & 0x3fffffffffffffffL) | 0x8000000000000000L // variant=0b10 | rand_b (62)
    new UUID(msb, lsb)
  }

  def generate(using UUIDGen[IO], Clock[IO]): IO[UUID] =
    for {
      millis <- Clock[IO].realTime.map(_.toMillis)
      random <- UUIDGen[IO].randomUUID
    } yield fromParts(millis, random)
}
