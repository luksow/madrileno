package madrileno.auth.emails

import madrileno.user.domain.FullName
import madrileno.utils.mailer.*
import scalatags.Text.all.*

class WelcomeEmailTemplate(fullName: Option[FullName]) extends EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail = lang match {
    case Language.En =>
      val subject  = fullName.fold("Welcome!")(n => s"Welcome, $n!")
      val greeting = fullName.fold("Hi,")(n => s"Hi $n,")
      RenderedMail(
        subject = subject,
        body = MailBody.Html(
          EmailLayout.page(
            subject,
            p(greeting),
            p("Thanks for joining! We're excited to have you on board."),
            p("You can get started by exploring the platform:"),
            EmailLayout.cta(ctx.baseUrl.toString, "Get Started")
          )
        )
      )
  }
}

object WelcomeEmailTemplate {
  val preview: MailPreview = MailPreview("welcome-email", WelcomeEmailTemplate(Some(FullName("Jane Doe"))))
}
