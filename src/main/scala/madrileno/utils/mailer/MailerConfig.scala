package madrileno.utils.mailer

import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

case class MailerConfig(
  host: String,
  port: Int = 587,
  username: Option[String] = None,
  password: Option[String] = None,
  fromAddress: String,
  fromName: Option[String] = None,
  tls: Boolean = true)

object MailerConfig {
  given ConfigReader[MailerConfig] = deriveReader[MailerConfig]
}
