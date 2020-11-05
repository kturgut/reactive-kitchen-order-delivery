package cloudkitchens.kitchen

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Duration, LocalDateTime}

import cloudkitchens.order.Order

case class PackagedProduct(remainingShelfLife:Float, value:Float, createdOn:LocalDateTime, updatedOn:LocalDateTime, order:Order) {
  import PackagedProduct._
  override def toString() = s"Product (name:${order.name}, temp:${order.temp}, remainingShelfLife:$remainingShelfLife, value:$value, " +
    s"createdOn:${createdOn.format(dateFormatter)}, lastUpdatedOn:${updatedOn.format(dateFormatter)}, orderId:${order.id})"
  def prettyString = s"Product (${order.name} temp:${order.temp} remainingShelfLife:$remainingShelfLife, value:$value)"

  implicit val ordering = PackagedProduct.IncreasingValue
  val MillliSecondsInOneSecond = 1000

  def phantomCopy(shelfDecayModifier:Float, current:LocalDateTime = LocalDateTime.now()):PackagedProduct = {
    val duration = ChronoUnit.MILLIS.between(updatedOn,current).toFloat / MillliSecondsInOneSecond
    val remainingLife = math.max(0,if (remainingShelfLife <=0) 0 else (remainingShelfLife - duration - order.decayRate * duration * shelfDecayModifier))
    val newValue = math.max(0,if (order.shelfLife<=0) 0f else remainingLife.toFloat / order.shelfLife)
    copy(remainingLife, newValue, createdOn, current, order:Order)
  }

  def expirationInMillis(shelfDecayModifier:Float) = {
    val incrementInMillis = 200 // TODO compute proper value based on max expected life of products
    var futureTime = updatedOn
    var newValue = value
    while (newValue > 0) {
      val duration = ChronoUnit.MILLIS.between(updatedOn,futureTime).toFloat / MillliSecondsInOneSecond
      val remainingLife = math.max(0,if (remainingShelfLife <=0) 0 else (remainingShelfLife - duration - order.decayRate * duration * shelfDecayModifier))
      newValue = math.max(0,if (order.shelfLife<=0) 0f else remainingLife.toFloat / order.shelfLife)
      futureTime = futureTime.plus(incrementInMillis, ChronoUnit.MILLIS )
    }
    Duration.between(futureTime,updatedOn).toMillis
  }

  // How many more millis before expected before pickup: (lowerBound,upperBound)
  // If negative, means delivery window has passed.
  def minimalWaitTimeForPickup(time:LocalDateTime=LocalDateTime.now()): (Long,Long) = {
    val soonestExpectedArrival = createdOn.plus(2, ChronoUnit.SECONDS )
    val pickupTimeDiff = Duration.between(soonestExpectedArrival,time).toMillis
    (pickupTimeDiff, pickupTimeDiff + 4000)
  }
}

case object PackagedProduct{

  val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    object IncreasingValue extends Ordering[PackagedProduct] {
    def compare(a:PackagedProduct, b:PackagedProduct):Int =
      Ordering.Tuple2(Ordering.Float, Ordering.String).compare((a.value, a.order.id),  (b.value, b.order.id))
  }
  def apply(order:Order, time:LocalDateTime = LocalDateTime.now()):PackagedProduct = {
    new PackagedProduct(order.shelfLife,1, time, time, order)
  }
}