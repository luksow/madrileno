package madrileno.auth.domain

import pl.iterators.kebs.opaque.Opaque

opaque type FirebaseJwt <: String = String
object FirebaseJwt extends Opaque[FirebaseJwt, String]
