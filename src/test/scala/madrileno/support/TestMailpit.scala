package madrileno.support

import cats.effect.IO
import com.dimafeng.testcontainers.GenericContainer
import io.circe.parser
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

  private def requireSuccess(response: sttp.client4.Response[String], context: String): io.circe.Json = {
    if (!response.code.isSuccess)
      throw new RuntimeException(s"Mailpit $context failed: ${response.code} ${response.body}")
    parser.parse(response.body) match {
      case Right(json) => json
      case Left(error) => throw new RuntimeException(s"Mailpit $context returned invalid JSON: $error")
    }
  }

  def clearMailpit(): IO[Unit] = IO.blocking {
    val response = quickRequest.delete(uri"$apiUrl/messages").send()
    if (!response.code.isSuccess)
      throw new RuntimeException(s"Mailpit clear failed: ${response.code} ${response.body}")
  }

  def getMessages: IO[Seq[MailpitMessage]] = IO.blocking {
    val json = requireSuccess(quickRequest.get(uri"$apiUrl/messages").send(), "GET /messages")
    json.hcursor
      .downField("messages")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .map { msg =>
        MailpitMessage(id = msg.hcursor.get[String]("ID").getOrElse(""), subject = msg.hcursor.get[String]("Subject").getOrElse(""))
      }
  }

  def getMessage(id: String): IO[MailpitMessageDetail] = IO.blocking {
    val json = requireSuccess(quickRequest.get(uri"$apiUrl/message/$id").send(), s"GET /message/$id")
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
    def poll(remaining: FiniteDuration): IO[Seq[MailpitMessage]] =
      getMessages.flatMap { messages =>
        if (condition(messages)) IO.pure(messages)
        else if (remaining <= Duration.Zero) IO.raiseError(new RuntimeException("Timed out waiting for mail in Mailpit"))
        else IO.sleep(interval) *> poll(remaining - interval)
      }
    poll(timeout)
  }
}
