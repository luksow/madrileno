package madrileno.utils.mailer

import scalatags.Text.TypedTag
import scalatags.Text.all.*

/** One shared look for every transactional email, aligned with the frontend's design tokens (its `src/styles/tailwind.css`). Email clients support
  * neither oklch nor CSS variables, so the tokens are resolved to hex here — rebrand by editing them.
  */
object EmailLayout {
  private val Canvas            = "#f5f5f5" // --muted
  private val Surface           = "#ffffff" // --background
  private val Foreground        = "#0a0a0a" // --foreground
  private val MutedForeground   = "#737373" // --muted-foreground
  private val Primary           = "#772938" // --primary
  private val PrimaryForeground = "#fafafa" // --primary-foreground
  private val BorderColor       = "#e5e5e5" // --border
  private val Radius            = "10px" // --radius (0.625rem)

  private val FontStack =
    "'Geist Variable', Geist, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

  private val styles = s"""
    body { margin: 0; padding: 0; background-color: $Canvas; color: $Foreground; font-family: $FontStack; -webkit-font-smoothing: antialiased; }
    .canvas { padding: 40px 16px; background-color: $Canvas; }
    .card { max-width: 570px; margin: 0 auto; background: $Surface; border: 1px solid $BorderColor; border-radius: $Radius; overflow: hidden; }
    .brand { padding: 20px 32px; border-bottom: 1px solid $BorderColor; color: $Primary; font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
    .content { padding: 32px; line-height: 1.6; }
    .content h1 { margin: 0 0 16px; font-size: 22px; font-weight: 600; letter-spacing: -0.01em; color: $Foreground; }
    .content p { margin: 0 0 16px; }
    .highlight { font-weight: 600; color: $Primary; }
    .cta { padding: 8px 0 4px; }
    .button { display: inline-block; background: $Primary; color: $PrimaryForeground; text-decoration: none; padding: 12px 24px; border-radius: $Radius; font-weight: 600; font-size: 14px; }
    .footer { padding: 20px 32px; border-top: 1px solid $BorderColor; color: $MutedForeground; font-size: 13px; }
    .footer p { margin: 0; }
  """

  /** The wordmark mirrors the frontend header; `init-project` renames it with everything else. */
  def page(heading: String, blocks: Frag*): TypedTag[String] =
    html(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width, initial-scale=1"),
        meta(name := "color-scheme", content := "light"),
        tag("style")(raw(styles))
      ),
      body(
        div(cls := "canvas")(
          div(cls := "card")(
            div(cls := "brand")("madrileno"),
            div(cls := "content")(h1(heading), blocks),
            div(cls := "footer")(p("This email was sent automatically. Please do not reply."))
          )
        )
      )
    )

  def cta(url: String, label: String): TypedTag[String] =
    div(cls := "cta")(a(cls := "button", href := url)(label))

  def highlight(value: String): TypedTag[String] = span(cls := "highlight")(value)
}
