package madrileno.utils.crypto

import cats.effect.IO
import cats.effect.std.UUIDGen
import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

object IdGenerator {
  def generateId[T](opaque: Opaque[T, UUID])(using UUIDGen[IO]): IO[T] = UUIDGen[IO].randomUUID.map(opaque.apply)
}
