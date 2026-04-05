package madrileno.support

import com.dimafeng.testcontainers.GenericContainer
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.wait.strategy.Wait
import sttp.client4.quick.*

trait TestMailpit extends BeforeAndAfterAll { self: Suite =>

  private lazy val mailpitContainer: GenericContainer =
    GenericContainer
      .Def(dockerImage = "axllent/mailpit", exposedPorts = Seq(1025, 8025), waitStrategy = Wait.forHttp("/api/v1/messages").forPort(8025))
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

  def clearMailpit(): Unit = quickRequest.delete(uri"$apiUrl/messages").send(): Unit

  def getMailpitMessages: Json = {
    val response = quickRequest.get(uri"$apiUrl/messages").send()
    io.circe.parser.parse(response.body).getOrElse(Json.Null)
  }

  def getMailpitMessage(id: String): Json = {
    val response = quickRequest.get(uri"$apiUrl/message/$id").send()
    io.circe.parser.parse(response.body).getOrElse(Json.Null)
  }

  def mailpitMessageCount: Int = getMailpitMessages.hcursor.get[Int]("messages_count").getOrElse(0)
}
