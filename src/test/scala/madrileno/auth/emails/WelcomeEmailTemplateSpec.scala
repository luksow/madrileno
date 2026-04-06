package madrileno.auth.emails

import madrileno.user.domain.FullName
import madrileno.utils.mailer.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI

class WelcomeEmailTemplateSpec extends AnyWordSpec with Matchers {
  private val ctx = MailContext(baseUrl = URI("https://example.com"))

  "WelcomeEmailTemplate" should {
    "render with full name" in {
      val rendered = WelcomeEmailTemplate(Some(FullName("Alice"))).render(ctx, Language.En)

      rendered.subject shouldBe "Welcome, Alice!"
      val html = rendered.body match {
        case MailBody.Html(h) => h.render
        case other            => fail(s"Expected Html body, got $other")
      }
      html should include("Hi Alice,")
      html should include("https://example.com")
    }

    "render without name" in {
      val rendered = WelcomeEmailTemplate(None).render(ctx, Language.En)

      rendered.subject shouldBe "Welcome!"
      val html = rendered.body match {
        case MailBody.Html(h) => h.render
        case other            => fail(s"Expected Html body, got $other")
      }
      html should include("Hi,")
      html should not include "null"
    }

    "include base URL in CTA" in {
      val customCtx = MailContext(baseUrl = URI("https://myapp.io"))
      val rendered  = WelcomeEmailTemplate(Some(FullName("Bob"))).render(customCtx, Language.En)

      val html = rendered.body match {
        case MailBody.Html(h) => h.render
        case other            => fail(s"Expected Html body, got $other")
      }
      html should include("https://myapp.io")
    }

    "have no inline attachments" in {
      val rendered = WelcomeEmailTemplate(Some(FullName("Test"))).render(ctx, Language.En)
      rendered.inlineAttachments shouldBe empty
    }
  }
}
