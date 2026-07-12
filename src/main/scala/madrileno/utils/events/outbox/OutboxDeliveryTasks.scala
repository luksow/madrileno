package madrileno.utils.events.outbox

object OutboxDeliveryTasks {
  val TaskNamePrefix: String = "outbox-deliver:"

  def taskName(consumer: String): String = s"$TaskNamePrefix$consumer"

  def taskInstance(eventId: DomainEventId): String = eventId.unwrap.toString
}
