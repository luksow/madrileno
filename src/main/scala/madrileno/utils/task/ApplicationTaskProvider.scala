package madrileno.utils.task

trait ApplicationTaskProvider extends RecurringTaskProvider with OneTimeTaskProvider with CustomTaskProvider {
  override def recurringTasks: List[Task[?]] = Nil

  override def oneTimeTasks: List[OneTimeTask[?]] = Nil

  override def customTasks: List[CustomTask[?]] = Nil
}

trait RecurringTaskProvider {
  def recurringTasks: List[Task[?]]
}

trait OneTimeTaskProvider {
  def oneTimeTasks: List[OneTimeTask[?]]
}

trait CustomTaskProvider {
  def customTasks: List[CustomTask[?]]
}
