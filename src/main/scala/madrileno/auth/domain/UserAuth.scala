package madrileno.auth.domain

import io.circe.Json
import madrileno.user.domain.*
import pl.iterators.kebs.opaque.Opaque

import java.net.URI
import java.util.UUID

opaque type UserAuthId = UUID
object UserAuthId extends Opaque[UserAuthId, UUID]

opaque type Provider = String
object Provider extends Opaque[Provider, String] {
  val Firebase: Provider = Provider("firebase")
  val Dev: Provider      = Provider("dev")
}

opaque type ProviderUserId = String
object ProviderUserId extends Opaque[ProviderUserId, String]

opaque type Credential = String
object Credential extends Opaque[Credential, String]

opaque type Metadata = Json
object Metadata extends Opaque[Metadata, Json] {
  def empty: Metadata = Json.obj()
}

final case class ExternalProfile(
  fullName: Option[FullName],
  emailAddress: Option[EmailAddress],
  emailVerified: Boolean,
  avatarUrl: Option[URI])

final case class VerifiedExternalToken(
  provider: Provider,
  providerUserId: ProviderUserId,
  credential: Credential,
  profile: ExternalProfile,
  metadata: Metadata)

final case class UserAuth(
  id: UserAuthId,
  userId: UserId,
  provider: Provider,
  providerUserId: ProviderUserId,
  credential: Credential,
  metadata: Metadata)

object UserAuth {
  def apply(
    id: UserAuthId,
    userId: UserId,
    externalToken: VerifiedExternalToken
  ): UserAuth =
    UserAuth(id, userId, externalToken.provider, externalToken.providerUserId, externalToken.credential, externalToken.metadata)
}
