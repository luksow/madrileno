package __package__.__aggregate__.domain

import pl.iterators.kebs.opaque.Opaque

import java.util.UUID

opaque type __Aggregate__Id = UUID
object __Aggregate__Id extends Opaque[__Aggregate__Id, UUID]

opaque type __Aggregate__Name = String
object __Aggregate__Name extends Opaque[__Aggregate__Name, String] {
  override def validate(value: String): Either[String, __Aggregate__Name] = {
    if (value.trim.nonEmpty) Right(value.trim)
    else Left("__Aggregate__ name must not be empty")
  }
}

enum RenameRejection {
  case NameUnchanged
}

final case class __Aggregate__(
  id: __Aggregate__Id,
  name: __Aggregate__Name) {

  def rename(newName: __Aggregate__Name): Either[RenameRejection, __Aggregate__] =
    if (newName == name) Left(RenameRejection.NameUnchanged)
    else Right(copy(name = newName))
}
