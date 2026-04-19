package madrileno.auction.domain

import pl.iterators.kebs.opaque.Opaque

opaque type Rating = BigDecimal
object Rating extends Opaque[Rating, BigDecimal] {
  override def validate(value: BigDecimal): Either[String, Rating] = {
    if (value >= BigDecimal(0) && value <= BigDecimal(5)) Right(value)
    else Left("Rating must be between 0 and 5")
  }
}

opaque type RatingsCount = Int
object RatingsCount extends Opaque[RatingsCount, Int] {
  override def validate(value: Int): Either[String, RatingsCount] = {
    if (value >= 0) Right(value)
    else Left("Ratings count must be non-negative")
  }
}

final case class VivinoRating(rating: Rating, ratingsCount: RatingsCount)
