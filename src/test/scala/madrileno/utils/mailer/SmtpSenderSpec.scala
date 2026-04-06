package madrileno.utils.mailer

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestMailpit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class SmtpSenderSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestMailpit {

  private lazy val sender = SmtpSender(MailerConfig(host = mailpitHost, port = mailpitSmtpPort, fromAddress = "test@example.com", tls = false))

  "SmtpSender" should {
    "send plain text email" in {
      val mail =
        SerializedMail(to = List("user@example.com"), subject = "Plain text test", body = SerializedMailBody.Text("Hello, this is plain text."))

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
      } yield {
        messages should have size 1
        messages.head.subject shouldBe "Plain text test"
      }
    }

    "send HTML email" in {
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "HTML test",
        body = SerializedMailBody.Html("<html><body><h1>Hello</h1><p>World</p></body></html>")
      )

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
        detail   <- getMessage(messages.head.id)
      } yield {
        detail.html should include("<h1>Hello</h1>")
        detail.html should include("<p>World</p>")
      }
    }

    "send multipart email with both text and HTML" in {
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Both test",
        body = SerializedMailBody.Both("Plain version", "<html><body><b>HTML version</b></body></html>")
      )

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
        detail   <- getMessage(messages.head.id)
      } yield {
        detail.html should include("<b>HTML version</b>")
        detail.text should include("Plain version")
      }
    }

    "send email with attachment" in {
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Attachment test",
        body = SerializedMailBody.Text("See attached."),
        attachments = List(Attachment("hello.txt", "text/plain", "Hello from file!".getBytes("UTF-8")))
      )

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
        detail   <- getMessage(messages.head.id)
      } yield {
        detail.attachmentCount shouldBe 1
      }
    }

    "send email with inline attachment" in {
      val imageData = Array.fill(100)(0x42.toByte)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Inline test",
        body = SerializedMailBody.Html("<html><body><img src='cid:test-img'/></body></html>"),
        inlineAttachments = List(InlineAttachment("test-img", "image.bin", "application/octet-stream", imageData))
      )

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
        detail   <- getMessage(messages.head.id)
      } yield {
        detail.html should include("cid:test-img")
        detail.inlineCount shouldBe 1
      }
    }

    "set correct recipients" in {
      val mail = SerializedMail(
        to = List("a@example.com", "b@example.com"),
        subject = "Multi-recipient",
        body = SerializedMailBody.Text("Hello both"),
        cc = List("cc@example.com")
      )

      for {
        _        <- clearMailpit()
        _        <- sender.send(mail)
        messages <- getMessages
      } yield {
        messages should have size 1
      }
    }
  }
}
