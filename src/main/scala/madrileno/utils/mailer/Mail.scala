package madrileno.utils.mailer

import scalatags.Text.TypedTag

import java.net.URI

enum Language {
  case En
}

case class MailContext(baseUrl: URI)

enum MailBody {
  case Text(text: String)
  case Html(html: TypedTag[String])
  case Both(text: String, html: TypedTag[String])
}

case class RenderedMail(
  subject: String,
  body: MailBody,
  inlineAttachments: List[InlineAttachment] = Nil)

case class Attachment(
  filename: String,
  contentType: String,
  data: Array[Byte])

case class InlineAttachment(
  contentId: String,
  filename: String,
  contentType: String,
  data: Array[Byte])

case class Mail(
  to: List[String],
  rendered: RenderedMail,
  from: Option[String] = None,
  cc: List[String] = Nil,
  bcc: List[String] = Nil,
  replyTo: Option[String] = None,
  attachments: List[Attachment] = Nil)
