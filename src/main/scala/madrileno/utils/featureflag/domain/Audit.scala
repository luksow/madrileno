package madrileno.utils.featureflag.domain

import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type AuditEntryId = UUID
object AuditEntryId extends Opaque[AuditEntryId, UUID]

opaque type Actor = String
object Actor extends Opaque[Actor, String] {
  override def validate(value: String): Either[String, Actor] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) Left("Actor must be non-empty") else Right(trimmed)
  }
}

enum AuditAction {
  case Created, Updated, Deleted, Toggled
}

enum AuditSortField {
  case CreatedAt
}

final case class AuditEntry(
  id: AuditEntryId,
  flagId: Option[FlagId],
  flagKey: FlagKey,
  actor: Actor,
  action: AuditAction,
  before: Option[FeatureFlag],
  after: Option[FeatureFlag],
  createdAt: Instant)
