package madrileno.utils.mailer

import cats.effect.IO
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.*

import java.util.Base64

class Mailer(
  smtpSender: SmtpSender,
  schedulerClient: SchedulerClient
)(using TelemetryContext)
    extends LoggingSupport {

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

  private given Encoder[MailBody] = Encoder.instance {
    case MailBody.Text(text)       => Json.obj("type" -> "text".asJson, "text" -> text.asJson)
    case MailBody.Html(html)       => Json.obj("type" -> "html".asJson, "html" -> html.render.asJson)
    case MailBody.Both(text, html) => Json.obj("type" -> "both".asJson, "text" -> text.asJson, "html" -> html.render.asJson)
  }
  private given Decoder[MailBody] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "text" => c.downField("text").as[String].map(MailBody.Text(_))
      case "html" => c.downField("html").as[String].map(s => MailBody.Html(scalatags.Text.all.span(scalatags.Text.all.raw(s))))
      case "both" =>
        for {
          text <- c.downField("text").as[String]
          html <- c.downField("html").as[String]
        } yield MailBody.Both(text, scalatags.Text.all.span(scalatags.Text.all.raw(html)))
      case other => Left(io.circe.DecodingFailure(s"Unknown MailBody type: $other", c.history))
    }
  }

  private given Encoder[RenderedMail] = Encoder.instance { rm =>
    Json.obj("subject" -> rm.subject.asJson, "body" -> rm.body.asJson, "inlineAttachments" -> rm.inlineAttachments.asJson)
  }
  private given Decoder[RenderedMail] = Decoder.instance { c =>
    for {
      subject           <- c.downField("subject").as[String]
      body              <- c.downField("body").as[MailBody]
      inlineAttachments <- c.downField("inlineAttachments").as[List[InlineAttachment]]
    } yield RenderedMail(subject, body, inlineAttachments)
  }

  private given Encoder[Mail] = Encoder.instance { m =>
    Json.obj(
      "to" -> m.to.asJson,
      "rendered" -> m.rendered.asJson,
      "from" -> m.from.asJson,
      "cc" -> m.cc.asJson,
      "bcc" -> m.bcc.asJson,
      "replyTo" -> m.replyTo.asJson,
      "attachments" -> m.attachments.asJson
    )
  }
  private given Decoder[Mail] = Decoder.instance { c =>
    for {
      to          <- c.downField("to").as[List[String]]
      rendered    <- c.downField("rendered").as[RenderedMail]
      from        <- c.downField("from").as[Option[String]]
      cc          <- c.downField("cc").as[List[String]]
      bcc         <- c.downField("bcc").as[List[String]]
      replyTo     <- c.downField("replyTo").as[Option[String]]
      attachments <- c.downField("attachments").as[List[Attachment]]
    } yield Mail(to, rendered, from, cc, bcc, replyTo, attachments)
  }

  val sendMailTask: OneTimeTask[Mail] = Task.oneTime(TaskDescriptor[Mail]("send-mail")) { task =>
    logger.info(s"Sending email to ${task.payload.to.mkString(", ")}: ${task.payload.rendered.subject}") *>
      smtpSender.send(task.payload)
  }

  def send(mail: Mail): IO[Boolean] =
    schedulerClient.schedule(sendMailTask.instance(instanceId(mail), mail))

  def sendInSession(mail: Mail): DB[Boolean] =
    schedulerClient.scheduleInSession(sendMailTask.instance(instanceId(mail), mail))

  def sendTransactionally(mail: Mail): DBInTransaction[Boolean] =
    schedulerClient.scheduleTransactionally(sendMailTask.instance(instanceId(mail), mail))

  private def instanceId(mail: Mail): String =
    s"mail-${mail.to.headOption.getOrElse("unknown")}-${System.nanoTime()}"
}
