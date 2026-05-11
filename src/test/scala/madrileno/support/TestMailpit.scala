package madrileno.support

import cats.effect.IO
import com.dimafeng.testcontainers.GenericContainer
import io.circe.{ACursor, Json, parser}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.wait.strategy.Wait
import sttp.client4.Response
import sttp.client4.quick.*

import scala.concurrent.duration.*

final case class MailpitAddress(name: String, address: String)
final case class MailpitMessage(
  id: String,
  subject: String,
  from: Option[MailpitAddress],
  to: List[MailpitAddress],
  cc: List[MailpitAddress],
  bcc: List[MailpitAddress],
  snippet: String,
  size: Long,
  attachmentCount: Int)
final case class MailpitMessageDetail(
  subject: String,
  text: String,
  html: String,
  from: Option[MailpitAddress],
  to: List[MailpitAddress],
  cc: List[MailpitAddress],
  bcc: List[MailpitAddress],
  replyTo: List[MailpitAddress],
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

  private def requireSuccess(response: Response[String], context: String): Json = {
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

  private def parseAddress(json: Json): MailpitAddress =
    MailpitAddress(name = json.hcursor.get[String]("Name").getOrElse(""), address = json.hcursor.get[String]("Address").getOrElse(""))

  private def parseAddressList(cursor: ACursor): List[MailpitAddress] =
    cursor.focus.flatMap(_.asArray).getOrElse(Vector.empty).map(parseAddress).toList

  private def parseAddressOpt(cursor: ACursor): Option[MailpitAddress] =
    cursor.focus.filterNot(_.isNull).map(parseAddress)

  def getMessages: IO[Seq[MailpitMessage]] = IO.blocking {
    val json = requireSuccess(quickRequest.get(uri"$apiUrl/messages").send(), "GET /messages")
    json.hcursor
      .downField("messages")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .map { msg =>
        val c = msg.hcursor
        MailpitMessage(
          id = c.get[String]("ID").getOrElse(""),
          subject = c.get[String]("Subject").getOrElse(""),
          from = parseAddressOpt(c.downField("From")),
          to = parseAddressList(c.downField("To")),
          cc = parseAddressList(c.downField("Cc")),
          bcc = parseAddressList(c.downField("Bcc")),
          snippet = c.get[String]("Snippet").getOrElse(""),
          size = c.get[Long]("Size").getOrElse(0L),
          attachmentCount = c.get[Int]("Attachments").getOrElse(0)
        )
      }
  }

  def getMessage(id: String): IO[MailpitMessageDetail] = IO.blocking {
    val json = requireSuccess(quickRequest.get(uri"$apiUrl/message/$id").send(), s"GET /message/$id")
    val c    = json.hcursor
    MailpitMessageDetail(
      subject = c.get[String]("Subject").getOrElse(""),
      text = c.get[String]("Text").getOrElse(""),
      html = c.get[String]("HTML").getOrElse(""),
      from = parseAddressOpt(c.downField("From")),
      to = parseAddressList(c.downField("To")),
      cc = parseAddressList(c.downField("Cc")),
      bcc = parseAddressList(c.downField("Bcc")),
      replyTo = parseAddressList(c.downField("ReplyTo")),
      attachmentCount = c.downField("Attachments").focus.flatMap(_.asArray).map(_.size).getOrElse(0),
      inlineCount = c.downField("Inline").focus.flatMap(_.asArray).map(_.size).getOrElse(0)
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
