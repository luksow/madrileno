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
          EmailLayout.page(
            "You've been outbid!",
            p("Someone has placed a higher bid on ", EmailLayout.highlight(wineName.toString), "."),
            p("The new highest bid is ", EmailLayout.highlight(formattedAmount), "."),
            p("Don't miss out — place a new bid to stay in the running!"),
            EmailLayout.cta(ctx.baseUrl.toString, "Place a New Bid")
          )
        )
      )
  }
}

object OutbidEmailTemplate {
  val preview: MailPreview =
    MailPreview("outbid-notification", OutbidEmailTemplate(WineName("Château Margaux 2015"), Price(BigDecimal(350)), Currency.getInstance("EUR")))
}
