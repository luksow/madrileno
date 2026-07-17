package madrileno.utils.events.outbox

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}
import madrileno.utils.task.SchedulerClient

class OutboxRecovery(
  dispatcher: OutboxDispatcher,
  deliveryRepository: OutboxDeliveryRepository,
  schedulerClient: SchedulerClient,
  transactor: Transactor,
  config: OutboxConfig
)(using TelemetryContext)
    extends LoggingSupport {

  def recoverOnce: IO[Unit] =
    missingLedgerRows *> strandedPendingRows

  def run: Resource[IO, Unit] =
    (recoverOnce.handleErrorWith(e => logger.error(e)("outbox recovery pass failed")) *>
      IO.sleep(config.recoveryInterval)).foreverM.background.void

  private def missingLedgerRows: IO[Unit] =
    dispatcher.consumers.traverse_ { consumer =>
      transactor
        .inSession(deliveryRepository.deliveriesMissingFor(consumer, dispatcher.eventTypesFor(consumer), config.recoveryBatchSize))
        .flatMap {
          _.traverse_ { event =>
            Clock[IO].realTimeInstant.flatMap { now =>
              transactor.inTransaction {
                deliveryRepository.openDelivery(event.id, consumer, now).flatMap {
                  case true  => schedulerClient.scheduleTransactionally(dispatcher.instanceFor(consumer, event.id)).void
                  case false => IO.unit
                }
              }
            }
          }
        }
    }

  private def strandedPendingRows: IO[Unit] =
    transactor
      .inSession(deliveryRepository.pendingWithoutTask(OutboxDeliveryTasks.TaskNamePrefix, config.recoveryBatchSize))
      .flatMap {
        _.traverse_ { case (eventId, consumer) =>
          if (dispatcher.consumers.contains(consumer))
            schedulerClient.schedule(dispatcher.instanceFor(consumer, eventId)).void
          else
            logger.warn(s"pending outbox delivery for unregistered consumer $consumer (event $eventId), leaving for drain")
        }
      }
}
