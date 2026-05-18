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

final case class __Aggregate__(
  id: __Aggregate__Id,
  name: __Aggregate__Name)
