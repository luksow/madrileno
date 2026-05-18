package __package__.__aggregate__.services

import cats.effect.IO
import __package__.__aggregate__.domain.{__Aggregate__, __Aggregate__Id}
import __package__.__aggregate__.repositories.__Aggregate__Repository
import __package__.utils.db.transactor.Transactor

class __Aggregate__Service(__aggregate__Repository: __Aggregate__Repository, transactor: Transactor) {
  def find(id: __Aggregate__Id): IO[Option[__Aggregate__]] =
    transactor.inSession(__aggregate__Repository.find(id))
}
