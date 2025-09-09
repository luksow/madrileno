package madrileno.auth.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.auth.domain.*
import madrileno.utils.json.JsonProtocol.*

case class RefreshTokenDto(
  id: RefreshTokenId,
  userAgent: UserAgent,
  ipAddress: String)
    derives Encoder.AsObject

object RefreshTokenDto {
  def apply(refreshToken: RefreshToken): RefreshTokenDto = {
    refreshToken.into[RefreshTokenDto].withFieldComputed(_.ipAddress, _.ipAddress.toString).transform
  }
}
