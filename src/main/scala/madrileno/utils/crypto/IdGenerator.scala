package madrileno.utils.crypto

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

object IdGenerator {
  def generateId[T](opaque: Opaque[T, UUID])(using UUIDGen[IO], Clock[IO]): IO[T] = UuidV7.generate.map(opaque.apply)
}
