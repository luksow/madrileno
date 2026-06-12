package madrileno.utils.featureflag.domain

import pl.iterators.kebs.opaque.Opaque

import java.time.Instant
import java.util.UUID

opaque type SegmentId = UUID
object SegmentId extends Opaque[SegmentId, UUID]

opaque type SegmentName = String
object SegmentName extends Opaque[SegmentName, String] {
  override def validate(value: String): Either[String, SegmentName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty || trimmed.length > 128) Left("SegmentName must be 1-128 chars") else Right(trimmed)
  }
}

final case class Segment(
  id: SegmentId,
  name: SegmentName,
  description: FlagDescription,
  conditions: List[RuleCondition],
  createdAt: Instant,
  updatedAt: Instant) {
  def updated(now: Instant): Segment = copy(updatedAt = now)
}
