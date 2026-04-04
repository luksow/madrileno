package madrileno.utils.mailer

import scalatags.Text.TypedTag

import java.net.URI

enum Language {
  case En
}

case class MailContext(baseUrl: URI)

trait EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail
}

enum MailBody {
  case Text(text: String)
  case Html(html: TypedTag[String])
  case Both(text: String, html: TypedTag[String])

  def serialize: SerializedMailBody = this match {
    case Text(text)       => SerializedMailBody.Text(text)
    case Html(html)       => SerializedMailBody.Html(html.render)
    case Both(text, html) => SerializedMailBody.Both(text, html.render)
  }
}

enum SerializedMailBody {
  case Text(text: String)
  case Html(html: String)
  case Both(text: String, html: String)
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

case class SerializedMail(
  to: List[String],
  subject: String,
  body: SerializedMailBody,
  from: Option[String] = None,
  cc: List[String] = Nil,
  bcc: List[String] = Nil,
  replyTo: Option[String] = None,
  attachments: List[Attachment] = Nil,
  inlineAttachments: List[InlineAttachment] = Nil)
