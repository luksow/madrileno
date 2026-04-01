package madrileno.utils.mailer

import cats.effect.IO
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.server.Route

import java.util.Base64

class MailPreviewRouter(previews: List[MailPreview]) extends BaseRouter {
  val routes: Route =
    (get & pathPrefix("mail-previews")) {
      pathEndOrSingleSlash {
        complete {
          IO.pure(Ok -> previews.map(_.name))
        }
      } ~
        path(Segment) { name =>
          complete {
            previews.find(_.name == name) match {
              case Some(preview) =>
                val rendered = preview.render()
                var emailHtml = rendered.body match {
                  case MailBody.Text(text) => s"<pre>$text</pre>"
                  case MailBody.Html(h)    => h.render
                  case MailBody.Both(_, h) => h.render
                }
                rendered.inlineAttachments.foreach { att =>
                  val dataUri = s"data:${att.contentType};base64,${Base64.getEncoder.encodeToString(att.data)}"
                  emailHtml = emailHtml.replace(s"cid:${att.contentId}", dataUri)
                }
                val page = s"""<!DOCTYPE html>
                  |<html>
                  |<head>
                  |  <meta charset="UTF-8">
                  |  <title>${rendered.subject}</title>
                  |  <style>
                  |    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 0; background: #f5f5f5; }
                  |    .preview-banner { background: #1a1a2e; color: #eee; padding: 12px 24px; font-size: 13px; }
                  |    .preview-banner strong { color: #fff; }
                  |    .preview-subject { background: #16213e; color: #fff; padding: 16px 24px; font-size: 18px; font-weight: 600; }
                  |    .preview-body { max-width: 800px; margin: 24px auto; background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); overflow: hidden; }
                  |  </style>
                  |</head>
                  |<body>
                  |  <div class="preview-banner">Mail Preview: <strong>$name</strong></div>
                  |  <div class="preview-subject">${rendered.subject}</div>
                  |  <div class="preview-body">$emailHtml</div>
                  |</body>
                  |</html>""".stripMargin
                IO.pure(Ok -> page)
              case None =>
                IO.pure(NotFound -> s"Preview '$name' not found")
            }
          }
        }
    }
}
