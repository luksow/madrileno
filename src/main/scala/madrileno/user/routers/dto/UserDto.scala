package madrileno.user.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.user.domain.{EmailAddress, FullName, User, UserId}
import madrileno.utils.json.JsonProtocol.*

import java.net.URI

final case class UserDto(
  id: UserId,
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[URI])
    derives Encoder.AsObject,
      Decoder

object UserDto {
  def apply(user: User): UserDto = user.into[UserDto].transform
}
