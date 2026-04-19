package madrileno.auction.emails

import madrileno.auction.domain.{Price, WineName}
import madrileno.utils.mailer.*
import scalatags.Text.all.*

import java.util.Currency

class OutbidEmailTemplate(
  wineName: WineName,
  newBidAmount: Price,
  currency: Currency)
    extends EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail = lang match {
    case Language.En =>
      val formattedAmount = s"${currency.getSymbol} $newBidAmount"
      RenderedMail(
        subject = s"You've been outbid on $wineName",
        body = MailBody.Html(
          html(
            head(tag("style")(raw("""
              body { margin: 0; padding: 0; background-color: #f4f4f7; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
              .container { max-width: 570px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.08); }
              .header { background: #92400e; padding: 32px; text-align: center; }
              .header h1 { color: #ffffff; margin: 0; font-size: 24px; font-weight: 600; }
              .body-content { padding: 32px; color: #333; line-height: 1.6; }
              .body-content p { margin: 0 0 16px; }
              .highlight { font-weight: 600; color: #92400e; }
              .button { display: inline-block; background: #92400e; color: #ffffff; text-decoration: none; padding: 12px 32px; border-radius: 6px; font-weight: 600; font-size: 14px; }
              .cta { text-align: center; padding: 8px 0 24px; }
              .footer { padding: 24px 32px; background: #f8f9fa; color: #6b7280; font-size: 13px; text-align: center; }
            """))),
            body(
              div(cls := "container")(
                div(cls := "header")(h1("You've been outbid!")),
                div(cls := "body-content")(
                  p("Someone has placed a higher bid on ", span(cls := "highlight")(wineName.toString), "."),
                  p("The new highest bid is ", span(cls := "highlight")(formattedAmount), "."),
                  p("Don't miss out — place a new bid to stay in the running!"),
                  div(cls := "cta")(a(cls := "button", href := ctx.baseUrl.toString)("Place a New Bid"))
                ),
                div(cls := "footer")(p("This email was sent automatically. Please do not reply."))
              )
            )
          )
        )
      )
  }
}

object OutbidEmailTemplate {
  val preview: MailPreview =
    MailPreview("outbid-notification", OutbidEmailTemplate(WineName("Château Margaux 2015"), Price(BigDecimal(350)), Currency.getInstance("EUR")))
}
