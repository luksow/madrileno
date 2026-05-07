package madrileno.utils.storage

import pl.iterators.kebs.opaque.Opaque

import scala.concurrent.duration.FiniteDuration

opaque type SignedUrlTtl = FiniteDuration
object SignedUrlTtl extends Opaque[SignedUrlTtl, FiniteDuration] {
  override def validate(value: FiniteDuration): Either[String, SignedUrlTtl] =
    if (value.toMillis > 0) Right(value) else Left("SignedUrlTtl must be a positive duration")
}
