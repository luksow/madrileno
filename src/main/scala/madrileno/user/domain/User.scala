package madrileno.user.domain

import cats.effect.IO
import cats.effect.std.UUIDGen
import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

opaque type UserId = UUID
object UserId extends Opaque[UserId, UUID] {
  def generate(using UUIDGen[IO]): IO[UserId] = UUIDGen[IO].randomUUID.map(apply)
}

opaque type EmailAddress = String
object EmailAddress extends Opaque[EmailAddress, String] {
  override def validate(value: String): Either[String, EmailAddress] = {
    if (value.contains("@") && value.trim.length > 2) Right(value.trim)
    else Left("Invalid email address")
  }
}

final case class User(id: UserId, emailAddress: EmailAddress)
