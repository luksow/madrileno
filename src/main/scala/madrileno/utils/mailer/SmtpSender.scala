package madrileno.utils.mailer

import cats.effect.IO
import jakarta.mail.*
import jakarta.mail.internet.*

import java.util.Properties

class SmtpSender(config: MailerConfig) {
  def send(mail: Mail): IO[Unit] = IO.blocking {
    val session = createSession()
    val message = buildMessage(mail, session)
    Transport.send(message)
  }

  private def createSession(): Session = {
    val props = new Properties()
    props.put("mail.smtp.host", config.host)
    props.put("mail.smtp.port", config.port.toString)
    props.put("mail.smtp.auth", config.username.isDefined.toString)
    if (config.tls) {
      props.put("mail.smtp.starttls.enable", "true"): Unit
    }

    val authenticator = for {
      user <- config.username
      pass <- config.password
    } yield new Authenticator {
      override def getPasswordAuthentication: PasswordAuthentication =
        new PasswordAuthentication(user, pass)
    }

    authenticator.fold(Session.getInstance(props))(auth => Session.getInstance(props, auth))
  }

  private def buildMessage(mail: Mail, session: Session): MimeMessage = {
    val message = new MimeMessage(session)

    val fromAddress = mail.from.getOrElse(config.fromAddress)
    val fromPersonal = config.fromName.orNull
    message.setFrom(new InternetAddress(fromAddress, fromPersonal))

    mail.to.foreach(addr => message.addRecipient(Message.RecipientType.TO, new InternetAddress(addr)))
    mail.cc.foreach(addr => message.addRecipient(Message.RecipientType.CC, new InternetAddress(addr)))
    mail.bcc.foreach(addr => message.addRecipient(Message.RecipientType.BCC, new InternetAddress(addr)))
    mail.replyTo.foreach(addr => message.setReplyTo(Array(new InternetAddress(addr))))

    message.setSubject(mail.rendered.subject, "UTF-8")

    val inlineAtts = mail.rendered.inlineAttachments
    val regularAtts = mail.attachments

    val bodyContent = buildBodyContent(mail.rendered.body, inlineAtts)

    if (regularAtts.isEmpty) {
      message.setContent(bodyContent)
    } else {
      val mixed = new MimeMultipart("mixed")
      val bodyPart = new MimeBodyPart()
      bodyPart.setContent(bodyContent)
      mixed.addBodyPart(bodyPart)

      regularAtts.foreach { att =>
        val part = new MimeBodyPart()
        part.setDataHandler(new jakarta.activation.DataHandler(
          new jakarta.mail.util.ByteArrayDataSource(att.data, att.contentType)
        ))
        part.setFileName(att.filename)
        part.setDisposition(Part.ATTACHMENT)
        mixed.addBodyPart(part)
      }

      message.setContent(mixed)
    }

    message
  }

  private def buildBodyContent(body: MailBody, inlineAtts: List[InlineAttachment]): MimeMultipart = {
    body match {
      case MailBody.Text(text) =>
        val mp = new MimeMultipart("mixed")
        val part = new MimeBodyPart()
        part.setText(text, "UTF-8")
        mp.addBodyPart(part)
        mp

      case MailBody.Html(html) =>
        buildHtmlWithInline(html.render, inlineAtts)

      case MailBody.Both(text, html) =>
        val alternative = new MimeMultipart("alternative")
        val textPart = new MimeBodyPart()
        textPart.setText(text, "UTF-8")
        alternative.addBodyPart(textPart)

        if (inlineAtts.isEmpty) {
          val htmlPart = new MimeBodyPart()
          htmlPart.setContent(html.render, "text/html; charset=UTF-8")
          alternative.addBodyPart(htmlPart)
          alternative
        } else {
          val related = buildHtmlWithInline(html.render, inlineAtts)
          val relatedPart = new MimeBodyPart()
          relatedPart.setContent(related)
          alternative.addBodyPart(relatedPart)
          alternative
        }
    }
  }

  private def buildHtmlWithInline(htmlContent: String, inlineAtts: List[InlineAttachment]): MimeMultipart = {
    val related = new MimeMultipart(if (inlineAtts.nonEmpty) "related" else "mixed")
    val htmlPart = new MimeBodyPart()
    htmlPart.setContent(htmlContent, "text/html; charset=UTF-8")
    related.addBodyPart(htmlPart)

    inlineAtts.foreach { att =>
      val part = new MimeBodyPart()
      part.setDataHandler(new jakarta.activation.DataHandler(
        new jakarta.mail.util.ByteArrayDataSource(att.data, att.contentType)
      ))
      part.setContentID(s"<${att.contentId}>")
      part.setDisposition(Part.INLINE)
      part.setFileName(att.filename)
      related.addBodyPart(part)
    }

    related
  }
}
