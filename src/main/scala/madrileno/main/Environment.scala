package madrileno.main

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveEnumerationReader

enum Environment {
  case Dev, Test, Staging, Prod
}

object Environment {
  given ConfigReader[Environment] = deriveEnumerationReader[Environment]
}
