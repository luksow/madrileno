package madrileno.utils.mailer

import cats.effect.std.UUIDGen
import cats.effect.{Clock, IO}
import io.circe.{Codec, Decoder, Encoder}
import madrileno.utils.db.transactor.{DB, DBInTransaction}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.*

import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

class Mailer(
  smtpSender: SmtpSender,
  schedulerClient: SchedulerClient,
  context: MailContext
)(using
  Clock[IO],
  TelemetryContext,
  UUIDGen[IO])
    extends LoggingSupport {

  val sendMailTask: OneTimeTask[SerializedMail] =
    Task.oneTime(TaskDescriptor[SerializedMail]("send-mail")) { task =>
      logger.info(s"Sending email to ${task.payload.to.mkString(", ")}: ${task.payload.subject}") *>
        smtpSender.send(task.payload)
    }

  def send(
    mail: MailRequest,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): IO[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.schedule(sendMailTask.instance(s"mail-$uuid", serialize(mail), at = resolveTime(now, at, in)))
      }
    }

  def sendInSession(
    mail: MailRequest,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): DB[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.scheduleInSession(sendMailTask.instance(s"mail-$uuid", serialize(mail), at = resolveTime(now, at, in)))
      }
    }

  def sendTransactionally(
    mail: MailRequest,
    at: Option[Instant] = None,
    in: Option[FiniteDuration] = None
  ): DBInTransaction[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      UUIDGen[IO].randomUUID.flatMap { uuid =>
        schedulerClient.scheduleTransactionally(sendMailTask.instance(s"mail-$uuid", serialize(mail), at = resolveTime(now, at, in)))
      }
    }

  private def resolveTime(
    now: Instant,
    at: Option[Instant],
    in: Option[FiniteDuration]
  ): Option[Instant] =
    at.orElse(in.map(d => now.plusMillis(d.toMillis)))

  private def serialize(mail: MailRequest): SerializedMail = {
    val rendered = mail.template.render(context, mail.lang)
    SerializedMail(
      to = mail.to,
      subject = rendered.subject,
      body = rendered.body.serialize,
      from = mail.from,
      cc = mail.cc,
      bcc = mail.bcc,
      replyTo = mail.replyTo,
      attachments = mail.attachments,
      inlineAttachments = rendered.inlineAttachments
    )
  }

  private given Codec[Array[Byte]] =
    Codec.from(Decoder.decodeString.map(Base64.getDecoder.decode), Encoder.encodeString.contramap(Base64.getEncoder.encodeToString))
  private given Codec[InlineAttachment]   = Codec.AsObject.derived
  private given Codec[Attachment]         = Codec.AsObject.derived
  private given Codec[SerializedMailBody] = Codec.AsObject.derived
  private given Codec[SerializedMail]     = Codec.AsObject.derived
}
