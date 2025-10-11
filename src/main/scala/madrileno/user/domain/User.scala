package madrileno.user.domain

import madrileno.auth.domain.{ExternalProfile, VerifiedExternalToken}
import pl.iterators.kebs.opaque.Opaque

import java.net.URI
import java.time.Instant
import java.util.UUID

opaque type UserId = UUID
object UserId extends Opaque[UserId, UUID]

opaque type EmailAddress = String
object EmailAddress extends Opaque[EmailAddress, String] {
  override def validate(value: String): Either[String, EmailAddress] = {
    if (value.contains("@") && value.trim.length > 2) Right(value.trim)
    else Left("Invalid email address")
  }
}

opaque type FullName = String
object FullName extends Opaque[FullName, String] {
  override def validate(value: String): Either[String, FullName] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("Invalid full name")
  }
}

final case class User(
  id: UserId,
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[URI],
  blockedAt: Option[Instant]) {
  def isActive: Boolean = blockedAt.isEmpty
  def withUpdatedProfile(externalProfile: ExternalProfile): User = {
    copy(
      fullName = externalProfile.fullName.orElse(this.fullName),
      emailAddress = externalProfile.emailAddress.orElse(this.emailAddress),
      emailVerified = externalProfile.emailVerified || this.emailVerified,
      avatarUrl = externalProfile.avatarUrl.orElse(this.avatarUrl)
    )
  }
}

object User {
  def apply(id: UserId, externalToken: VerifiedExternalToken): User = {
    User(
      id,
      externalToken.profile.fullName,
      externalToken.profile.emailAddress,
      externalToken.profile.emailVerified,
      externalToken.profile.avatarUrl,
      None
    )
  }
}
