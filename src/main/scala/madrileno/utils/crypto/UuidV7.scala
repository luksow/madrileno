package madrileno.utils.crypto

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}

import java.util.UUID

object UuidV7 {

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
