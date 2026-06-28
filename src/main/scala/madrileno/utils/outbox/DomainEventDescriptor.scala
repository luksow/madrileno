package madrileno.utils.outbox

import io.circe.Json

import java.util.UUID

trait DomainEventDescriptor[A] {
  def eventType: String // versioned, e.g. "user-account-deleted.v1"
  def aggregateType: String
  def aggregateId(a: A): UUID
  def encode(a: A): Json
}
