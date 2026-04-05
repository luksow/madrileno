package madrileno.utils.mailer

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.support.TestTransactor
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class MailSerializationSpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  given Meter[IO]            = Meter.noop[IO]
  given TelemetryContext     = TelemetryContext(Meter.noop[IO], Tracer.noop[IO], null)

  private val capturedMail = new AtomicReference[SerializedMail]()

  private val capturingSender = new SmtpSender(MailerConfig(host = "localhost", fromAddress = "test@test.com")) {
    override def send(mail: SerializedMail): IO[Unit] = IO(capturedMail.set(mail))
  }

  private lazy val scheduler = Scheduler(transactor, SchedulerConfig(pollingInterval = 1.second))
  private lazy val client    = scheduler.client
  private lazy val context   = MailContext(baseUrl = URI("https://example.com"))
  private lazy val mailer    = new Mailer(capturingSender, client, context)

  "Mail serialization round-trip through scheduler" should {
    "preserve HTML email content" in {
      import scalatags.Text.all.{a as stA, *}

      val template: EmailTemplate = (ctx, _) =>
        RenderedMail(
          subject = "Test subject",
          body = MailBody.Html(html(body(h1("Hello"), p("World ", strong("bold")), stA(href := ctx.baseUrl.toString)("Link"))))
        )

      for {
        _ <- scheduler
               .run(oneTimeTasks = List(mailer.sendMailTask))
               .use { _ =>
                 for {
                   _ <- mailer.send(to = List("user@example.com"), template = template, lang = Language.En)
                   _ <- IO.sleep(3.seconds)
                 } yield ()
               }
      } yield {
        val captured = capturedMail.get()
        captured should not be null
        captured.to shouldBe List("user@example.com")
        captured.subject shouldBe "Test subject"
        captured.body match {
          case SerializedMailBody.Html(h) =>
            h should include("<h1>")
            h should include("Hello")
            h should include("<strong>bold</strong>")
            h should include("https://example.com")
          case other => fail(s"Expected Html, got $other")
        }
      }
    }
  }
}
