package cloudkitchens.kitchen

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import cloudkitchens.order.Order

case class Product(value:Float, remainingShelfLife:Float, createdOn:LocalDateTime, lastUpdated:LocalDateTime, order:Order) {
  import Product._
  override def toString() = s"Product (name:${order.name}, value:$value, createdOn:${createdOn.format(dateFormatter)}, lastUpdatedOn:${lastUpdated.format(dateFormatter)}, orderId:${order.id})"

  implicit val ordering = Product.IncreasingValue

  def updatedCopy(previousShelf:Shelf, current:LocalDateTime = LocalDateTime.now()):Product = {
    val diff = elapsedTimeInMillis(current).toFloat / 1000
    val newValue = (remainingShelfLife - diff -
      order.decayRate * diff.toFloat * previousShelf.decayModifier) / remainingShelfLife
    copy(newValue, remainingShelfLife * newValue, createdOn, current, order:Order)
  }
  def elapsedTimeInMillis(currentTime:LocalDateTime = LocalDateTime.now()):Long = ChronoUnit.MILLIS.between(createdOn,currentTime)
}

case object Product{
  val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  object IncreasingValue extends Ordering[Product] {
    def compare(a:Product, b:Product):Int = a.value compare b.value
  }
  def apply(order:Order, time:LocalDateTime = LocalDateTime.now()):Product = {
    new Product(1, order.shelfLife.toFloat, time, time, order)
  }
}