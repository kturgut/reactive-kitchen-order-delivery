package reactive.storage

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Duration, LocalDateTime}

import reactive.delivery.Courier
import reactive.order.{Order, Temperature}

final case class PackagedProduct(remainingShelfLife: Float, value: Float, createdOn: LocalDateTime, updatedOn: LocalDateTime, order: Order, pickupWindowInMillis:(Long,Long)) {

  import PackagedProduct._

  override def toString() = s"Product (name:${order.name}, temp:${order.temp}, remainingShelfLife:$remainingShelfLife, value:$value, " +
    s"createdOn:${createdOn.format(dateFormatter)}, lastUpdatedOn:${updatedOn.format(dateFormatter)}, id:${order.id})"

  def prettyString = s"Product (${order.name} temp:${order.temp} remainingShelfLife:$remainingShelfLife, value:$value)"

  /**
   * Creates a copy of the packaged product representing the updated state in time assuming it stayed in
   * a particular shelf with a specific decayModifier constant.
   * remainingLife and value is updated. Original creation time stays the same.
   */
  def phantomCopy(shelfDecayModifier: Float, current: LocalDateTime = LocalDateTime.now()): PackagedProduct = {
    val duration = ChronoUnit.MILLIS.between(updatedOn, current).toFloat / MillliSecondsInOneSecond
    if (duration > 0) {
      val remainingLife = math.max(0, if (remainingShelfLife <= 0) 0 else (remainingShelfLife - duration - order.decayRate * duration * shelfDecayModifier))
      val newValue = math.max(0, if (order.shelfLife <= 0) 0f else remainingLife.toFloat / order.shelfLife)
      copy(remainingLife, newValue, createdOn, current, order: Order)
    }
    else this
  }

  def expirationInMillis(shelfDecayModifier: Float): Long = {
    val incrementInMillis = 50
    var futureTime = updatedOn
    var newValue = value
    while (newValue > 0) {
      val duration = ChronoUnit.MILLIS.between(updatedOn, futureTime).toFloat / MillliSecondsInOneSecond
      val remainingLife = math.max(0, if (remainingShelfLife <= 0) 0 else (remainingShelfLife - duration - order.decayRate * duration * shelfDecayModifier))
      newValue = math.max(0, if (order.shelfLife <= 0) 0f else remainingLife.toFloat / order.shelfLife)
      futureTime = futureTime.plus(incrementInMillis, ChronoUnit.MILLIS)
    }
    Duration.between(updatedOn, futureTime).toMillis
  }

}

case object PackagedProduct {

  val dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  implicit val ordering = PackagedProduct.IncreasingValue
  val MillliSecondsInOneSecond = 1000

  def apply(order: Order, deliveryWindow:(Long,Long), time: LocalDateTime = LocalDateTime.now()): PackagedProduct = {
    new PackagedProduct(order.shelfLife, 1, time, time, order, deliveryWindow)
  }

  object IncreasingValue extends Ordering[PackagedProduct] {
    def compare(a: PackagedProduct, b: PackagedProduct): Int =
      Ordering.Tuple2(Ordering.Float, Ordering.String).compare((a.value, a.order.id), (b.value, b.order.id))
  }
}

private[storage] case class ExpirationInfo(product: PackagedProduct, ifInCurrentShelf: Long, ifMovedToTarget: Long, timeInMillisTillPickup: (Long, Long), shelf: Shelf) {
  def wantsToMove(millisIntoFuture: Long) = !willExpireForSure(millisIntoFuture) && ifInCurrentShelf < ifMovedToTarget

  /**
   * Return true if not inside pick up window OR expiration cannot be avoided even if moved to other shelf
   */
  def willExpireForSure(millisIntoFuture: Long): Boolean = {
    val bestTime = math.max(ifInCurrentShelf, ifMovedToTarget)
    (bestTime - millisIntoFuture < 0) && (bestTime > timeInMillisTillPickup._1)
  }

  def okToMove(millisIntoFuture: Long):Boolean = !willExpireForSure(millisIntoFuture) && safeToMove(millisIntoFuture)

  private def safeToMove(millisIntoFuture: Long):Boolean = (ifMovedToTarget - millisIntoFuture) > timeInMillisTillPickup._1

}

private[storage] case class ProductPair(inOverflow: ExpirationInfo, inTarget: ExpirationInfo) {

  /**
   * Returns either one of the products that will expire for sure in given time, or None
   */
  def willExpireForSure(millisIntoFuture: Long): Option[(Shelf, PackagedProduct)] = {
    if (inOverflow.willExpireForSure(millisIntoFuture)) Some((overflow, inOverflow.product))
    else if (inTarget.willExpireForSure(millisIntoFuture)) Some(target, inTarget.product)
    else None
  }

  def overflow: Shelf = inOverflow.shelf

  def target: Shelf = inTarget.shelf

  /**
   * Returns whether it is recommended to swap the products to ensure either one of the products will improve
   * its shelf life if moved while not putting the other at risk of being in "critical zone" identified by threshold.
   * Note that this will return false if either of these products will expireForSure.
   */
  def swapRecommended(threshold: Long): Boolean = inOverflow.wantsToMove(threshold) && inTarget.okToMove(threshold) ||
    inTarget.wantsToMove(threshold) && inOverflow.okToMove(threshold)

  /**
   * Returns true if either of the products will expire for sure if not moved within a certain time frame 'threshold'
   */
  def inCriticalZone(threshold: Long): Boolean = inOverflow.willExpireForSure(threshold) || inTarget.willExpireForSure(threshold)
}

private[storage] case object ProductPair {
  /** assumed that s1 is overflow shelf based on the ShelfManager's current logic that it is only checking if
   * products in overflow should be moved to another shelf.
   */
  def apply(p1: PackagedProduct, s1: Shelf, p2: PackagedProduct, s2: Shelf): ProductPair = {
    assert(s1.canAccept(p1.order) && s1.supports.contains(Temperature.All))
    new ProductPair(
      ExpirationInfo(p1, p1.expirationInMillis(s1.decayModifier), p1.expirationInMillis(s2.decayModifier), p1.pickupWindowInMillis, s1),
      ExpirationInfo(p2, p2.expirationInMillis(s2.decayModifier), p2.expirationInMillis(s1.decayModifier), p2.pickupWindowInMillis, s2)
    )
  }
}