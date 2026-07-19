package madrileno.auction.emails

import madrileno.auction.domain.WineName
import madrileno.utils.mailer.*
import scalatags.Text.all.*

class AuctionCancelledEmailTemplate(wineName: WineName) extends EmailTemplate {
  def render(ctx: MailContext, lang: Language): RenderedMail = lang match {
    case Language.En =>
      RenderedMail(
        subject = s"Auction cancelled: $wineName",
        body = MailBody.Html(
          EmailLayout.page(
            "Auction cancelled",
            p("The auction for ", EmailLayout.highlight(wineName.toString), " has been cancelled because the seller's account was deleted."),
            p("Your bid no longer stands — browse other auctions to keep bidding."),
            EmailLayout.cta(ctx.baseUrl.toString, "Browse Auctions")
          )
        )
      )
  }
}

object AuctionCancelledEmailTemplate {
  val preview: MailPreview =
    MailPreview("auction-cancelled-account-deleted", AuctionCancelledEmailTemplate(WineName("Château Margaux 2015")))
}
