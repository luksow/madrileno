package madrileno.utils.mailer

import cats.effect.IO
import madrileno.utils.http.BaseRouter
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType, Response, Status}
import pl.iterators.stir.server.Route

import java.util.Base64

class MailPreviewRouter(previews: List[MailPreview], context: MailContext) extends BaseRouter {
  val routes: Route =
    (get & path("mail-previews") & pathEnd) {
      htmlPage(Ok, indexPage)
    } ~
      (get & path("mail-previews" / Segment) & parameter("lang".as[Language].optional)) { (name, lang) =>
        val language = lang.getOrElse(Language.En)
        previews.find(_.name == name) match {
          case Some(preview) => htmlPage(Ok, previewPage(name, preview.template.render(context, language), language))
          case None          => htmlPage(NotFound, notFoundPage(name))
        }
      }

  private def indexPage: String = {
    import scalatags.Text.all.{head as stHead, *}
    import scalatags.Text.tags2.title
    html(
      stHead(meta(charset := "UTF-8"), title("Mail Previews")),
      body(style := "font-family:system-ui,sans-serif;max-width:600px;margin:40px auto;padding:0 16px")(
        h2("Mail Previews"),
        ul(style := "padding-left:20px")(
          previews.sortBy(_.name).map(p => li(style := "padding:4px 0")(a(href := s"mail-previews/${p.name}")(p.name)))
        )
      )
    ).render
  }

  private def previewPage(
    previewName: String,
    rendered: RenderedMail,
    language: Language
  ): String = {
    import scalatags.Text.all.{head as stHead, *}
    import scalatags.Text.tags2.title
    val emailHtml = renderBody(rendered)
    val langOptions = Language.values.map { l =>
      option(value := l.toString, if (l == language) selected := "selected" else ())(l.toString)
    }
    html(
      stHead(meta(charset := "UTF-8"), title(rendered.subject)),
      body(style := "margin:0;font-family:system-ui,sans-serif;display:flex;flex-direction:column;height:100vh")(
        div(style := "padding:8px 16px;background:#eee;font-size:13px;display:flex;align-items:center;gap:12px;border-bottom:1px solid #ccc")(
          a(href := "/mail-previews")("< Back"),
          b(previewName),
          span(style := "color:#666")(s"Subject: ${rendered.subject}"),
          tag("select")(
            style := "margin-left:auto;font-size:13px",
            attr("onchange") := s"window.location.href='mail-previews/$previewName?lang='+this.value"
          )(langOptions)
        ),
        tag("iframe")(style := "flex:1;border:none;width:100%", attr("srcdoc") := emailHtml)
      )
    ).render
  }

  private def notFoundPage(previewName: String): String = {
    import scalatags.Text.all.{head as stHead, *}
    import scalatags.Text.tags2.title
    html(stHead(meta(charset := "UTF-8"), title("Not Found")), body(h1("404"), p(s"Preview '$previewName' not found"))).render
  }

  private def renderBody(rendered: RenderedMail): String = {
    val rawHtml = rendered.body match {
      case MailBody.Text(text) => s"<pre>$text</pre>"
      case MailBody.Html(h)    => h.render
      case MailBody.Both(_, h) => h.render
    }
    rendered.inlineAttachments.foldLeft(rawHtml) { (html, att) =>
      val dataUri = s"data:${att.contentType};base64,${Base64.getEncoder.encodeToString(att.data)}"
      html.replace(s"cid:${att.contentId}", dataUri)
    }
  }

  private def htmlPage(status: Status, html: String): Route =
    complete {
      Response[IO](status)
        .withEntity(html)(using EntityEncoder.stringEncoder[IO].withContentType(`Content-Type`(MediaType.text.html)))
    }
}
