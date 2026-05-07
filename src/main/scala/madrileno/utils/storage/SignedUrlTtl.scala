package madrileno.utils.storage

import pl.iterators.kebs.opaque.Opaque

import scala.concurrent.duration.FiniteDuration

opaque type SignedUrlTtl = FiniteDuration
object SignedUrlTtl extends Opaque[SignedUrlTtl, FiniteDuration] {
  // AWS SigV4 caps presigned URL lifetime at 7 days; sub-second values truncate to 0 in the SDK.
  private val MinSeconds = 1L
  private val MaxSeconds = 7L * 24 * 60 * 60

  override def validate(value: FiniteDuration): Either[String, SignedUrlTtl] =
    if (value.toSeconds < MinSeconds) Left("SignedUrlTtl must be at least 1 second")
    else if (value.toSeconds > MaxSeconds) Left("SignedUrlTtl must not exceed 7 days (AWS SigV4 limit)")
    else Right(value)

  extension (ttl: SignedUrlTtl) def asJavaDuration: java.time.Duration = java.time.Duration.ofSeconds(ttl.unwrap.toSeconds)
}
