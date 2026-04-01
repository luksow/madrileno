package madrileno.utils.mailer

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.*

import java.util.Base64

class Mailer(smtpSender: SmtpSender, schedulerClient: SchedulerClient)(using TelemetryContext) extends LoggingSupport {

  private given Encoder[Array[Byte]] = Encoder.encodeString.contramap(Base64.getEncoder.encodeToString)
  private given Decoder[Array[Byte]] = Decoder.decodeString.map(Base64.getDecoder.decode)

  private given Encoder[InlineAttachment] = Encoder.instance { a =>
    Json.obj("contentId" -> a.contentId.asJson, "filename" -> a.filename.asJson, "contentType" -> a.contentType.asJson, "data" -> a.data.asJson)
  }
  private given Decoder[InlineAttachment] = Decoder.instance { c =>
    for {
      contentId   <- c.downField("contentId").as[String]
      filename    <- c.downField("filename").as[String]
      contentType <- c.downField("contentType").as[String]
      data        <- c.downField("data").as[Array[Byte]]
    } yield InlineAttachment(contentId, filename, contentType, data)
  }

  private given Encoder[Attachment] = Encoder.instance { a =>
    Json.obj("filename" -> a.filename.asJson, "contentType" -> a.contentType.asJson, "data" -> a.data.asJson)
  }
  private given Decoder[Attachment] = Decoder.instance { c =>
    for {
      filename    <- c.downField("filename").as[String]
      contentType <- c.downField("contentType").as[String]
      data        <- c.downField("data").as[Array[Byte]]
    } yield Attachment(filename, contentType, data)
  }

  private given Encoder[SerializedMailBody] = Encoder.instance {
    case SerializedMailBody.Text(text)       => Json.obj("type" -> "text".asJson, "text" -> text.asJson)
    case SerializedMailBody.Html(html)       => Json.obj("type" -> "html".asJson, "html" -> html.asJson)
    case SerializedMailBody.Both(text, html) => Json.obj("type" -> "both".asJson, "text" -> text.asJson, "html" -> html.asJson)
  }
  private given Decoder[SerializedMailBody] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "text" => c.downField("text").as[String].map(SerializedMailBody.Text(_))
      case "html" => c.downField("html").as[String].map(SerializedMailBody.Html(_))
      case "both" =>
        for {
          text <- c.downField("text").as[String]
          html <- c.downField("html").as[String]
        } yield SerializedMailBody.Both(text, html)
      case other => Left(io.circe.DecodingFailure(s"Unknown MailBody type: $other", c.history))
    }
  }

  private given Encoder[SerializedMail] = Encoder.instance { m =>
    Json.obj(
      "to"                -> m.to.asJson,
      "subject"           -> m.subject.asJson,
      "body"              -> m.body.asJson,
      "from"              -> m.from.asJson,
      "cc"                -> m.cc.asJson,
      "bcc"               -> m.bcc.asJson,
      "replyTo"           -> m.replyTo.asJson,
      "attachments"       -> m.attachments.asJson,
      "inlineAttachments" -> m.inlineAttachments.asJson
    )
  }
  private given Decoder[SerializedMail] = Decoder.instance { c =>
    for {
      to                <- c.downField("to").as[List[String]]
      subject           <- c.downField("subject").as[String]
      body              <- c.downField("body").as[SerializedMailBody]
      from              <- c.downField("from").as[Option[String]]
      cc                <- c.downField("cc").as[List[String]]
      bcc               <- c.downField("bcc").as[List[String]]
      replyTo           <- c.downField("replyTo").as[Option[String]]
      attachments       <- c.downField("attachments").as[List[Attachment]]
      inlineAttachments <- c.downField("inlineAttachments").as[List[InlineAttachment]]
    } yield SerializedMail(to, subject, body, from, cc, bcc, replyTo, attachments, inlineAttachments)
  }

  val sendMailTask: OneTimeTask[SerializedMail] =
    Task.oneTime(TaskDescriptor[SerializedMail]("send-mail")) { task =>
      logger.info(s"Sending email to ${task.payload.to.mkString(", ")}: ${task.payload.subject}") *>
        smtpSender.send(task.payload)
    }

  def send(mail: Mail): IO[Boolean] = {
    val serialized = mail.serialize
    schedulerClient.schedule(sendMailTask.instance(instanceId(mail), serialized))
  }

  def sendInSession(mail: Mail): DB[Boolean] = {
    val serialized = mail.serialize
    schedulerClient.scheduleInSession(sendMailTask.instance(instanceId(mail), serialized))
  }

  def sendTransactionally(mail: Mail): DBInTransaction[Boolean] = {
    val serialized = mail.serialize
    schedulerClient.scheduleTransactionally(sendMailTask.instance(instanceId(mail), serialized))
  }

  private def instanceId(mail: Mail): String =
    s"mail-${mail.to.headOption.getOrElse("unknown")}-${System.nanoTime()}"
}
