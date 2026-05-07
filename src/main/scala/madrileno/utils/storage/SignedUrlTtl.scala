package madrileno.utils.storage

import pl.iterators.kebs.opaque.Opaque

import scala.concurrent.duration.FiniteDuration

opaque type SignedUrlTtl = FiniteDuration
object SignedUrlTtl extends Opaque[SignedUrlTtl, FiniteDuration]
