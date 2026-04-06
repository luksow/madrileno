package madrileno.support

import cats.effect.IO
import com.dimafeng.testcontainers.GenericContainer
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.wait.strategy.Wait
import sttp.client4.quick.*

import scala.concurrent.duration.*

case class MailpitMessage(id: String, subject: String)
case class MailpitMessageDetail(
  subject: String,
  text: String,
  html: String,
  attachmentCount: Int,
  inlineCount: Int)

trait TestMailpit extends BeforeAndAfterAll { self: Suite =>

  private lazy val mailpitContainer: GenericContainer =
    GenericContainer
      .Def(dockerImage = "axllent/mailpit:v1.21", exposedPorts = Seq(1025, 8025), waitStrategy = Wait.forHttp("/api/v1/messages").forPort(8025))
      .start()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = mailpitContainer // force start
  }

  override def afterAll(): Unit = {
    mailpitContainer.stop()
    super.afterAll()
  }

  def mailpitSmtpPort: Int = mailpitContainer.mappedPort(1025)
  def mailpitHost: String  = mailpitContainer.host

  private def apiUrl: String = s"http://${mailpitContainer.host}:${mailpitContainer.mappedPort(8025)}/api/v1"

  def clearMailpit(): Unit = {
    val _ = quickRequest.delete(uri"$apiUrl/messages").send()
  }

  def getMessages: Seq[MailpitMessage] = {
    val response = quickRequest.get(uri"$apiUrl/messages").send()
    val json     = io.circe.parser.parse(response.body).getOrElse(Json.Null)
    json.hcursor
      .downField("messages")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .map { msg =>
        MailpitMessage(id = msg.hcursor.get[String]("ID").getOrElse(""), subject = msg.hcursor.get[String]("Subject").getOrElse(""))
      }
  }

  def getMessage(id: String): MailpitMessageDetail = {
    val response = quickRequest.get(uri"$apiUrl/message/$id").send()
    val json     = io.circe.parser.parse(response.body).getOrElse(Json.Null)
    MailpitMessageDetail(
      subject = json.hcursor.get[String]("Subject").getOrElse(""),
      text = json.hcursor.get[String]("Text").getOrElse(""),
      html = json.hcursor.get[String]("HTML").getOrElse(""),
      attachmentCount = json.hcursor.downField("Attachments").focus.flatMap(_.asArray).map(_.size).getOrElse(0),
      inlineCount = json.hcursor.downField("Inline").focus.flatMap(_.asArray).map(_.size).getOrElse(0)
    )
  }

  def waitForMail(
    condition: Seq[MailpitMessage] => Boolean,
    timeout: FiniteDuration = 5.seconds,
    interval: FiniteDuration = 100.millis
  ): IO[Seq[MailpitMessage]] = {
    def poll(remaining: FiniteDuration): IO[Seq[MailpitMessage]] = {
      val messages = getMessages
      if (condition(messages)) IO.pure(messages)
      else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException("Timed out waiting for mail in Mailpit"))
      else IO.sleep(interval) *> poll(remaining - interval)
    }
    poll(timeout)
  }
}
