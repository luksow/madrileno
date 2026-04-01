package madrileno.utils.mailer

case class MailPreview(name: String, render: () => RenderedMail)

trait MailPreviewProvider {
  def mailPreviews: List[MailPreview]
}

trait ApplicationMailPreviewProvider extends MailPreviewProvider {
  override def mailPreviews: List[MailPreview] = Nil
}
