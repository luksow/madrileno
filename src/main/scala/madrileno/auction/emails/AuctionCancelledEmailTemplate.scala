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
                div(cls := "header")(h1("Auction cancelled")),
                div(cls := "body-content")(
                  p("The auction for ", span(cls := "highlight")(wineName.toString), " has been cancelled because the seller's account was deleted."),
                  p("Your bid no longer stands — browse other auctions to keep bidding."),
                  div(cls := "cta")(a(cls := "button", href := ctx.baseUrl.toString)("Browse Auctions"))
                ),
                div(cls := "footer")(p("This email was sent automatically. Please do not reply."))
              )
            )
          )
        )
      )
  }
}

object AuctionCancelledEmailTemplate {
  val preview: MailPreview =
    MailPreview("auction-cancelled-account-deleted", AuctionCancelledEmailTemplate(WineName("Château Margaux 2015")))
}
