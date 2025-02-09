package madrileno.auth.domain

import pl.iterators.kebs.opaque.Opaque

opaque type FirebaseJwtToken <: String = String
object FirebaseJwtToken extends Opaque[FirebaseJwtToken, String]
