package madrileno.auth.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.auth.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

final case class RefreshTokenDto(
  id: RefreshTokenId,
  userAgent: UserAgent,
  ipAddress: String,
  createdAt: Instant,
  expiresAt: Option[Instant])
    derives Encoder.AsObject,
      Decoder

object RefreshTokenDto {
  def apply(refreshToken: RefreshToken): RefreshTokenDto = {
    refreshToken.into[RefreshTokenDto].withFieldComputed(_.ipAddress, _.ipAddress.toString).transform
  }
}
