package madrileno.auth.routers.dto

import madrileno.auth.domain.*
import madrileno.utils.json.JsonProtocol.*

import java.time.Instant

final case class SessionDto(
  id: RefreshTokenFamilyId,
  userAgent: UserAgent,
  ipAddress: String,
  createdAt: Instant,
  refreshedAt: Instant,
  expiresAt: Instant)
    derives Encoder.AsObject,
      Decoder

object SessionDto {
  def apply(refreshToken: RefreshToken): SessionDto = {
    SessionDto(
      id = refreshToken.familyId,
      userAgent = refreshToken.userAgent,
      ipAddress = refreshToken.ipAddress.toString,
      createdAt = refreshToken.familyCreatedAt,
      refreshedAt = refreshToken.createdAt,
      expiresAt = refreshToken.expiresAt
    )
  }
}
