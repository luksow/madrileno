package madrileno.auth.emails

import madrileno.utils.mailer.{Language, MailBody, MailContext, MailPreview, RenderedMail}
import scalatags.Text.all.*

import java.net.URI

object WelcomeEmail {
  def apply(userName: String, activationToken: String, ctx: MailContext, lang: Language): RenderedMail =
    lang match {
      case Language.En =>
        RenderedMail(
          subject = s"Welcome, $userName!",
          body = MailBody.Html(
            html(
              head(
                tag("style")(raw("""
                  body { margin: 0; padding: 0; background-color: #f4f4f7; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
                  .container { max-width: 570px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.08); }
                  .header { background: #1a1a2e; padding: 32px; text-align: center; }
                  .header h1 { color: #ffffff; margin: 0; font-size: 24px; font-weight: 600; }
                  .body-content { padding: 32px; color: #333; line-height: 1.6; }
                  .body-content p { margin: 0 0 16px; }
                  .button { display: inline-block; background: #2563eb; color: #ffffff; text-decoration: none; padding: 12px 32px; border-radius: 6px; font-weight: 600; font-size: 14px; }
                  .cta { text-align: center; padding: 8px 0 24px; }
                  .footer { padding: 24px 32px; background: #f8f9fa; color: #6b7280; font-size: 13px; text-align: center; }
                """))
              ),
              body(
                div(cls := "container")(
                  div(cls := "header")(
                    h1(s"Welcome, $userName!")
                  ),
                  div(cls := "body-content")(
                    p(s"Hi $userName,"),
                    p("Thanks for joining! We're excited to have you on board."),
                    p("To get started, please activate your account by clicking the button below:"),
                    div(cls := "cta")(
                      a(cls := "button", href := s"${ctx.baseUrl}/activate/$activationToken")("Activate Your Account")
                    ),
                    p("If you didn't create this account, you can safely ignore this email.")
                  ),
                  div(cls := "footer")(
                    p("This email was sent automatically. Please do not reply.")
                  )
                )
              )
            )
          )
        )
    }

  val preview: MailPreview = MailPreview(
    "welcome-email",
    () =>
      apply(
        userName = "Alice Example",
        activationToken = "sample-token-abc123",
        ctx = MailContext(baseUrl = URI("https://example.com")),
        lang = Language.En
      )
  )
}
