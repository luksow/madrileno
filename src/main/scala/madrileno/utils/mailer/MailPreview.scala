package madrileno.utils.mailer

case class MailPreview(name: String, template: EmailTemplate)

trait MailPreviewProvider {
  def mailPreviews: List[MailPreview] = Nil
}
