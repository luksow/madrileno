package madrileno.auction.emails

import madrileno.auction.domain.{Price, WineName}
import madrileno.utils.mailer.*
import scalatags.Text.all.*

import java.util.Currency

class AuctionClosedEmailTemplate(
  wineName: WineName,
  message: String,
  ctaText: String)
    extends EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail = lang match {
    case Language.En =>
      RenderedMail(
        subject = s"Auction closed: $wineName",
        body = MailBody.Html(
          html(
            head(tag("style")(raw("""
              body { margin: 0; padding: 0; background-color: #f4f4f7; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
              .container { max-width: 570px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.08); }
              .header { background: #1a1a2e; padding: 32px; text-align: center; }
              .header h1 { color: #ffffff; margin: 0; font-size: 24px; font-weight: 600; }
              .body-content { padding: 32px; color: #333; line-height: 1.6; }
              .body-content p { margin: 0 0 16px; }
              .highlight { font-weight: 600; color: #1a1a2e; }
              .button { display: inline-block; background: #2563eb; color: #ffffff; text-decoration: none; padding: 12px 32px; border-radius: 6px; font-weight: 600; font-size: 14px; }
              .cta { text-align: center; padding: 8px 0 24px; }
              .footer { padding: 24px 32px; background: #f8f9fa; color: #6b7280; font-size: 13px; text-align: center; }
            """))),
            body(
              div(cls := "container")(
                div(cls := "header")(h1(s"Auction Closed: $wineName")),
                div(cls := "body-content")(p(message), div(cls := "cta")(a(cls := "button", href := ctx.baseUrl.toString)(ctaText))),
                div(cls := "footer")(p("This email was sent automatically. Please do not reply."))
              )
            )
          )
        )
      )
  }
}

object AuctionClosedEmailTemplate {
  def forSeller(
    wineName: WineName,
    winningAmount: Option[Price],
    currency: Currency
  ): AuctionClosedEmailTemplate = {
    val message = winningAmount match {
      case Some(amount) => s"Your auction for $wineName has closed. The winning bid was ${currency.getSymbol} $amount."
      case None         => s"Your auction for $wineName has closed with no bids."
    }
    AuctionClosedEmailTemplate(wineName, message, "View Your Auctions")
  }

  def forWinner(
    wineName: WineName,
    amount: Price,
    currency: Currency
  ): AuctionClosedEmailTemplate = {
    val message = s"Congratulations! You won the auction for $wineName with a bid of ${currency.getSymbol} $amount."
    AuctionClosedEmailTemplate(wineName, message, "View Your Wins")
  }

  val sellerPreview: MailPreview =
    MailPreview("auction-closed-seller", forSeller(WineName("Romanée-Conti 1945"), Some(Price(BigDecimal(25000))), Currency.getInstance("EUR")))

  val winnerPreview: MailPreview =
    MailPreview("auction-closed-winner", forWinner(WineName("Romanée-Conti 1945"), Price(BigDecimal(25000)), Currency.getInstance("EUR")))
}
