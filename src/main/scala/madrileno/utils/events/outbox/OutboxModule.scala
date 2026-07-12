package madrileno.utils.events.outbox

import madrileno.utils.db.transactor.Transactor
import madrileno.utils.observability.TelemetryContext
import madrileno.utils.task.{OneTimeTask, OneTimeTaskProvider, SchedulerClient}

trait OutboxSubscriptionProvider {
  def outboxSubscriptions: List[OutboxSubscription] = Nil
}

trait OutboxModule extends OneTimeTaskProvider with OutboxSubscriptionProvider {
  given telemetryContext: TelemetryContext
  val transactor: Transactor
  val schedulerClient: SchedulerClient
  lazy val outboxConfig: OutboxConfig

  private lazy val outboxRepository         = new OutboxRepository
  private lazy val outboxDeliveryRepository = new OutboxDeliveryRepository

  protected lazy val outboxDispatcher: OutboxDispatcher =
    new OutboxDispatcher(outboxSubscriptions, outboxRepository, outboxDeliveryRepository, transactor, outboxConfig)

  lazy val outbox: Outbox =
    new Outbox(outboxRepository, outboxDeliveryRepository, schedulerClient, outboxDispatcher)

  lazy val outboxRecovery: OutboxRecovery =
    new OutboxRecovery(outboxDispatcher, outboxDeliveryRepository, schedulerClient, transactor, outboxConfig)

  override abstract def oneTimeTasks: List[OneTimeTask[?]] = {
    super.oneTimeTasks ++ outboxDispatcher.deliveryTasks
  }
}
