package madrileno.user.domain

import madrileno.utils.events.outbox.DomainEventDescriptor
import madrileno.utils.events.{EventCodec, given}

final case class UserAccountDeleted(userId: UserId) derives EventCodec

object UserAccountDeleted {
  given DomainEventDescriptor[UserAccountDeleted] = DomainEventDescriptor("user-account-deleted.v1", "user", _.userId)
}
