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
        body = MailBody.Html(EmailLayout.page(s"Auction Closed: $wineName", p(message), EmailLayout.cta(ctx.baseUrl.toString, ctaText)))
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
