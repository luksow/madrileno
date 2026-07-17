package madrileno.utils.events.outbox

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import madrileno.utils.db.transactor.{DBInTransaction, Transactor}
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.{OneTimeTask, Task, TaskDescriptor}
import org.typelevel.otel4s.Attribute

import java.time.Instant
import java.util.UUID

class OutboxDispatcher(
  subscriptions: List[OutboxSubscription],
  outboxRepository: OutboxRepository,
  deliveryRepository: OutboxDeliveryRepository,
  transactor: Transactor,
  config: OutboxConfig
)(using TelemetryContext)
    extends LoggingSupport {

  private val duplicated: Set[(String, String)] =
    subscriptions.groupBy(s => (s.consumer, s.eventType)).collect { case (key, ss) if ss.sizeIs > 1 => key }.toSet
  require(duplicated.isEmpty, s"duplicate outbox subscriptions (consumer, eventType): ${duplicated.mkString(", ")}")

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
          deliveryRepository.markFailed(DomainEventId(task.payload), consumer, None, now) *>
            recordDelivery(consumer, "exhausted")
        }
      }
    )
  }

  private val taskByConsumer: Map[String, OneTimeTask[UUID]] =
    deliveryTasks.map(t => t.descriptor.taskName.stripPrefix(OutboxDeliveryTasks.TaskNamePrefix) -> t).toMap

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
            logger.warn(s"outbox delivery row missing for $eventId/$consumer, skipping").as(None)
          case Some(DeliveryStatus.Completed) | Some(DeliveryStatus.Failed) =>
            IO.pure(None)
          case Some(DeliveryStatus.Pending) =>
            Clock[IO].realTimeInstant.flatMap { now =>
              outboxRepository.loadEvent(eventId).flatMap {
                case None =>
                  deadLetter(eventId, consumer, "domain_event row missing", now)
                case Some(event) =>
                  subs.find(_.eventType == event.eventType) match {
                    case None =>
                      deadLetter(eventId, consumer, s"no subscription for ${event.eventType}", now)
                    case Some(sub) =>
                      sub.run(event, Delivery(eventId, consumer, attempt, event.occurredAt)).flatMap {
                        case Reaction.Done          => deliveryRepository.markCompleted(eventId, consumer, now).as(Some("completed"))
                        case Reaction.Drop(reason)  => deadLetter(eventId, consumer, reason, now)
                        case Reaction.Retry(reason) => IO.raiseError(OutboxRetryRequested(reason))
                      }
                  }
              }
            }
        }
      }
      .flatMap(_.traverse_(recordDelivery(consumer, _)))
      .handleErrorWith { error =>
        Clock[IO].realTimeInstant
          .flatMap(now =>
            transactor.inSession(deliveryRepository.recordError(eventId, consumer, Option(error.getMessage).getOrElse(error.getClass.getName), now))
          )
          .handleErrorWith(e => logger.warn(e)(s"failed to record delivery error for $eventId/$consumer"))
          .flatMap(_ => IO.raiseError(error))
      }

  private def deadLetter(
    eventId: DomainEventId,
    consumer: String,
    reason: String,
    now: Instant
  ): DBInTransaction[Option[String]] =
    logger.warn(s"dead-lettering outbox delivery $eventId/$consumer: $reason") *>
      deliveryRepository.markFailed(eventId, consumer, Some(reason), now).as(Some("dropped"))

  private def recordDelivery(consumer: String, outcome: String): IO[Unit] =
    summon[TelemetryContext].meter
      .counter[Long]("outbox.deliveries")
      .create
      .flatMap(_.inc(Attribute("consumer", consumer), Attribute("outcome", outcome)))
}
