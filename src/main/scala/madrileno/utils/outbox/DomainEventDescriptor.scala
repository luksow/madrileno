package madrileno.utils.outbox

import io.circe.Json

import java.util.UUID

trait DomainEventDescriptor[A] {
  def eventType: String
  def aggregateType: String
  def aggregateId(a: A): UUID
  def encode(a: A): Json
}
