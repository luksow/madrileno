package madrileno.utils.mailer

import pureconfig.*

case class MailerConfig(
  host: String,
  port: Int = 587,
  username: Option[String] = None,
  password: Option[String] = None,
  fromAddress: String,
  fromName: Option[String] = None,
  tls: Boolean = true)
    derives ConfigReader
