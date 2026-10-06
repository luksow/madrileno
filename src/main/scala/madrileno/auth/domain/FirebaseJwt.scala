package madrileno.auth.domain

import madrileno.utils.observability.Fingerprint
import pl.iterators.kebs.opaque.Opaque

opaque type FirebaseJwt <: String = String
object FirebaseJwt extends Opaque[FirebaseJwt, String] {
  extension (jwt: FirebaseJwt) {
    def fingerprint: Fingerprint = Fingerprint(jwt)
  }
}
