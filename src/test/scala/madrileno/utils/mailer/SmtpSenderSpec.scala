package madrileno.utils.mailer

import cats.effect.testing.scalatest.AsyncIOSpec
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.testcontainers.containers.wait.strategy.Wait
import sttp.client4.quick.*

class SmtpSenderSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestContainersForAll {

  override type Containers = GenericContainer

  override def startContainers(): GenericContainer = {
    val container = GenericContainer
      .Def(
        dockerImage = "axllent/mailpit",
        exposedPorts = Seq(1025, 8025),
        waitStrategy = Wait.forHttp("/api/v1/messages").forPort(8025)
      )
      .start()
    container
  }

  private def smtpPort(c: GenericContainer): Int = c.mappedPort(1025)
  private def apiUrl(c: GenericContainer): String = s"http://${c.host}:${c.mappedPort(8025)}/api/v1"

  private def sender(c: GenericContainer): SmtpSender =
    SmtpSender(MailerConfig(host = c.host, port = smtpPort(c), fromAddress = "test@example.com", tls = false))

  private def clearMessages(c: GenericContainer): Unit =
    quickRequest.delete(uri"${apiUrl(c)}/messages").send(): Unit

  private def getMessages(c: GenericContainer): Json = {
    val response = quickRequest.get(uri"${apiUrl(c)}/messages").send()
    io.circe.parser.parse(response.body).getOrElse(Json.Null)
  }

  private def getMessage(c: GenericContainer, id: String): Json = {
    val response = quickRequest.get(uri"${apiUrl(c)}/message/$id").send()
    io.circe.parser.parse(response.body).getOrElse(Json.Null)
  }

  "SmtpSender" should {
    "send plain text email" in withContainers { c =>
      clearMessages(c)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Plain text test",
        body = SerializedMailBody.Text("Hello, this is plain text.")
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val count    = messages.hcursor.get[Int]("messages_count").getOrElse(0)
        count shouldBe 1

        val msg = messages.hcursor.downField("messages").downArray
        msg.get[String]("Subject").toOption.get shouldBe "Plain text test"
      }
    }

    "send HTML email" in withContainers { c =>
      clearMessages(c)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "HTML test",
        body = SerializedMailBody.Html("<html><body><h1>Hello</h1><p>World</p></body></html>")
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val id       = messages.hcursor.downField("messages").downArray.get[String]("ID").toOption.get
        val detail   = getMessage(c, id)
        val html     = detail.hcursor.get[String]("HTML").toOption.getOrElse("")
        html should include("<h1>Hello</h1>")
        html should include("<p>World</p>")
      }
    }

    "send multipart email with both text and HTML" in withContainers { c =>
      clearMessages(c)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Both test",
        body = SerializedMailBody.Both("Plain version", "<html><body><b>HTML version</b></body></html>")
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val id       = messages.hcursor.downField("messages").downArray.get[String]("ID").toOption.get
        val detail   = getMessage(c, id)
        val html     = detail.hcursor.get[String]("HTML").toOption.getOrElse("")
        val text     = detail.hcursor.get[String]("Text").toOption.getOrElse("")
        html should include("<b>HTML version</b>")
        text should include("Plain version")
      }
    }

    "send email with attachment" in withContainers { c =>
      clearMessages(c)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Attachment test",
        body = SerializedMailBody.Text("See attached."),
        attachments = List(Attachment("hello.txt", "text/plain", "Hello from file!".getBytes("UTF-8")))
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val id       = messages.hcursor.downField("messages").downArray.get[String]("ID").toOption.get
        val detail   = getMessage(c, id)
        val attachments = detail.hcursor.downField("Attachments").focus.flatMap(_.asArray).map(_.size).getOrElse(0)
        attachments shouldBe 1
      }
    }

    "send email with inline attachment" in withContainers { c =>
      clearMessages(c)
      val imageData = Array.fill(100)(0x42.toByte)
      val mail = SerializedMail(
        to = List("user@example.com"),
        subject = "Inline test",
        body = SerializedMailBody.Html("<html><body><img src='cid:test-img'/></body></html>"),
        inlineAttachments = List(InlineAttachment("test-img", "image.bin", "application/octet-stream", imageData))
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val id       = messages.hcursor.downField("messages").downArray.get[String]("ID").toOption.get
        val detail   = getMessage(c, id)
        val html     = detail.hcursor.get[String]("HTML").toOption.getOrElse("")
        html should include("cid:test-img")
        val inlineCount = detail.hcursor.downField("Inline").focus.flatMap(_.asArray).map(_.size).getOrElse(0)
        inlineCount shouldBe 1
      }
    }

    "set correct recipients" in withContainers { c =>
      clearMessages(c)
      val mail = SerializedMail(
        to = List("a@example.com", "b@example.com"),
        subject = "Multi-recipient",
        body = SerializedMailBody.Text("Hello both"),
        cc = List("cc@example.com")
      )

      sender(c).send(mail).map { _ =>
        val messages = getMessages(c)
        val count    = messages.hcursor.get[Int]("messages_count").getOrElse(0)
        count shouldBe 1
      }
    }
  }
}
