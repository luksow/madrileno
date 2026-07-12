package madrileno.utils.events.outbox

import cats.effect.{Clock, IO}
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.{OneTimeTask, Task, TaskDescriptor}

import java.util.UUID

class OutboxDispatcher(
  subscriptions: List[OutboxSubscription],
  outboxRepository: OutboxRepository,
  deliveryRepository: OutboxDeliveryRepository,
  transactor: Transactor,
  config: OutboxConfig
)(using TelemetryContext)
    extends LoggingSupport {

  private val byConsumer: Map[String, List[OutboxSubscription]] = subscriptions.groupBy(_.consumer)

  val consumers: List[String] = byConsumer.keys.toList.sorted

  def eventTypesFor(consumer: String): List[String] = byConsumer.getOrElse(consumer, Nil).map(_.eventType).distinct

  def consumersFor(eventType: String): List[String] = subscriptions.filter(_.eventType == eventType).map(_.consumer).distinct

  val deliveryTasks: List[OneTimeTask[UUID]] = byConsumer.toList.sortBy(_._1).map { case (consumer, subs) =>
    OneTimeTask[UUID](
      descriptor = TaskDescriptor[UUID](OutboxDeliveryTasks.taskName(consumer)),
      execution = task => deliver(consumer, subs, DomainEventId(task.payload), task.consecutiveFailures.getOrElse(0)),
      maxRetries = config.deliveryMaxRetries,
      onAbandon = Some { task =>
        Clock[IO].realTimeInstant.flatMap { now =>
          deliveryRepository.markFailed(DomainEventId(task.payload), consumer, None, now)
        }
      }
    )
  }

  private val taskByConsumer: Map[String, OneTimeTask[UUID]] =
    deliveryTasks.map(t => t.descriptor.taskName -> t).toMap.map { case (name, t) => name.stripPrefix(OutboxDeliveryTasks.TaskNamePrefix) -> t }

  def instanceFor(consumer: String, eventId: DomainEventId): Task[UUID] =
    taskByConsumer(consumer).instance(OutboxDeliveryTasks.taskInstance(eventId), eventId.unwrap)

  private def deliver(
    consumer: String,
    subs: List[OutboxSubscription],
    eventId: DomainEventId,
    attempt: Int
  ): IO[Unit] =
    transactor
      .inTransaction {
        deliveryRepository.lockForDelivery(eventId, consumer).flatMap {
          case None =>
            logger.warn(s"outbox delivery row missing for $eventId/$consumer, skipping")
          case Some(DeliveryStatus.Completed) | Some(DeliveryStatus.Failed) =>
            IO.unit
          case Some(DeliveryStatus.Pending) =>
            Clock[IO].realTimeInstant.flatMap { now =>
              outboxRepository.loadEvent(eventId).flatMap {
                case None =>
                  deliveryRepository.markFailed(eventId, consumer, Some("domain_event row missing"), now)
                case Some(event) =>
                  subs.find(_.eventType == event.eventType) match {
                    case None =>
                      deliveryRepository.markFailed(eventId, consumer, Some(s"no subscription for ${event.eventType}"), now)
                    case Some(sub) =>
                      sub.run(event, Delivery(eventId, consumer, attempt, event.occurredAt)).flatMap {
                        case Reaction.Done          => deliveryRepository.markCompleted(eventId, consumer, now)
                        case Reaction.Drop(reason)  => deliveryRepository.markFailed(eventId, consumer, Some(reason), now)
                        case Reaction.Retry(reason) => IO.raiseError(OutboxRetryRequested(reason))
                      }
                  }
              }
            }
        }
      }
      .handleErrorWith { error =>
        Clock[IO].realTimeInstant
          .flatMap(now => transactor.inSession(deliveryRepository.recordError(eventId, consumer, error.getMessage, now)))
          .handleErrorWith(e => logger.warn(e)(s"failed to record delivery error for $eventId/$consumer"))
          .flatMap(_ => IO.raiseError(error))
      }
}
