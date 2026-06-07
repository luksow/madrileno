package madrileno.utils.featureflag

import pureconfig.ConfigReader

import java.net.URI

final case class UnleashSettings(
  url: URI,
  apiToken: String,
  appName: String,
  environment: String)
    derives ConfigReader
