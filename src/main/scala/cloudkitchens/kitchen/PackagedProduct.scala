package cloudkitchens.kitchen

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import cloudkitchens.order.Order

case class PackagedProduct(value:Float, remainingShelfLife:Float, createdOn:LocalDateTime, lastUpdated:LocalDateTime, order:Order) {
  import PackagedProduct._
  override def toString() = s"Product (name:${order.name}, value:$value, createdOn:${createdOn.format(dateFormatter)}, lastUpdatedOn:${lastUpdated.format(dateFormatter)}, orderId:${order.id})"

  implicit val ordering = PackagedProduct.IncreasingValue

  def updatedCopy(previousShelf:Shelf, current:LocalDateTime = LocalDateTime.now()):PackagedProduct = {
    val diff = elapsedTimeInMillis(current).toFloat / 1000
    val newValue = (remainingShelfLife - diff -
      order.decayRate * diff.toFloat * previousShelf.decayModifier) / remainingShelfLife
    copy(newValue, remainingShelfLife * newValue, createdOn, current, order:Order)
  }
  def elapsedTimeInMillis(currentTime:LocalDateTime = LocalDateTime.now()):Long = ChronoUnit.MILLIS.between(createdOn,currentTime)
}

case object PackagedProduct{
  val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  object IncreasingValue extends Ordering[PackagedProduct] {
    def compare(a:PackagedProduct, b:PackagedProduct):Int = a.value compare b.value
  }
  def apply(order:Order, time:LocalDateTime = LocalDateTime.now()):PackagedProduct = {
    new PackagedProduct(1, order.shelfLife.toFloat, time, time, order)
  }
}