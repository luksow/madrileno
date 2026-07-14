package madrileno.user.domain

import madrileno.utils.events.EventCodec
import madrileno.utils.events.EventCodec.given
import madrileno.utils.events.outbox.DomainEventDescriptor

final case class UserAccountDeleted(userId: UserId) derives EventCodec

object UserAccountDeleted {
  given DomainEventDescriptor[UserAccountDeleted] = DomainEventDescriptor("user-account-deleted.v1", "user", _.userId.unwrap)
}
