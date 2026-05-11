package madrileno.utils.mailer

final case class MailPreview(name: String, template: EmailTemplate)

trait MailPreviewProvider {
  def mailPreviews: List[MailPreview] = Nil
}
