package madrileno.auth.domain

import io.circe.Json
import madrileno.user.domain.UserId
import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

opaque type UserAuthId = UUID
object UserAuthId extends Opaque[UserAuthId, UUID]

enum Provider {
  case Firebase
}

opaque type ProviderUserId = String
object ProviderUserId extends Opaque[ProviderUserId, String]

opaque type Credential = String
object Credential extends Opaque[Credential, String]

opaque type Metadata = Json
object Metadata extends Opaque[Metadata, Json] {
  def empty: Metadata = Json.obj()
}

final case class UserAuth(
  id: UserAuthId,
  userId: UserId,
  provider: Provider,
  providerUserId: ProviderUserId,
  credential: Credential,
  metadata: Metadata)
