package madrileno.user.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.user.domain.{EmailAddress, FullName, User, UserId}
import madrileno.utils.json.JsonProtocol.*

case class UserDto(
  id: UserId,
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[String])
    derives Encoder.AsObject,
      Decoder

object UserDto {
  def apply(user: User): UserDto =
    user.into[UserDto].withFieldComputed(_.avatarUrl, _.avatarUrl.map(_.toString)).transform
}
