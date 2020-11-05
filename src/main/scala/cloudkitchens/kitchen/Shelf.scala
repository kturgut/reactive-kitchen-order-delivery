package cloudkitchens.kitchen

import java.time.{LocalDateTime, ZoneId}

import akka.event.LoggingAdapter
import cloudkitchens.kitchen.ShelfManager.{DiscardOrder, ExpiredShelfLife, ShelfCapacityExceeded}
import cloudkitchens.order.Temperature.{All, Cold, Frozen, Hot}
import cloudkitchens.order.{Order, Temperature}

import scala.collection.mutable


private [kitchen] case class Shelf(name: String, supports: Seq[Temperature],
                        capacity: Int = 10,
                        decayModifier: Int = 1,
                        var products: mutable.SortedSet[PackagedProduct] = mutable.SortedSet[PackagedProduct]()(PackagedProduct.IncreasingValue)) {

  def size:Int = products.size

  def canAccept(order: Order): Boolean = supports.contains(order.temperature)

  def hasAvailableSpace: Boolean = products.size < capacity

  def overCapacity: Boolean = products.size > capacity

  def highestValueProduct: PackagedProduct = products.last

  def lowestValueProduct: PackagedProduct = products.head

  def +=(elem: PackagedProduct): Shelf = {
    assert(canAccept(elem.order))
    products += elem;
    this
  }

  def -=(elem: PackagedProduct): Shelf = {
    products -= elem; this
  }

  def getPackageForOrder(order: Order): Option[PackagedProduct] = products.find(product => product.order.id == order.id)

  def expireEndOfLifeProducts(time: LocalDateTime): Seq[DiscardOrder] = {
    val discarded = this.products.filter(product => product.phantomCopy(decayModifier, time).value < 0).map(product => DiscardOrder(product.order, ExpiredShelfLife, time))
    val discardedOrders = discarded.map(_.order)
    products = this.products.filterNot(p => discardedOrders.contains(p.order))
    discarded.toSeq
  }

  implicit val localDateTimeOrdering: Ordering[LocalDateTime] = Ordering.by(x => x.atZone(ZoneId.of("UTC")).toEpochSecond)
  def productsOrderedByOrderDate:List[PackagedProduct] = products.toList.sortBy(_.order.createdOn)(localDateTimeOrdering)

  def reportContents(log:LoggingAdapter, verbose:Boolean=false): Unit = {
    log.info(s"$name shelf capacity utilization:${products.size} / $capacity, decay rate modifier:$decayModifier.")
    if (verbose) log.info(s"       contents: ${products.map(product=>(product.order.name,product.value)).mkString(",")}")
  }

}

private [kitchen] case object Shelf {
  def temperatureSensitiveShelves: mutable.Map[Temperature, Shelf] =
    mutable.Map(All -> overflow(), Hot -> hot(), Cold -> cold(), Frozen -> frozen())

  def hot(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Hot", Hot :: Nil, capacity, decayModifier)

  def cold(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Cold", Cold :: Nil, capacity, decayModifier)

  def frozen(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Frozen", Frozen :: Nil, capacity, decayModifier)

  def overflow(capacity: Int = 15, decayModifier: Int = 2) = Shelf("Overflow", All :: Hot :: Cold :: Frozen :: Nil, capacity, decayModifier)
}

case class ExpirationInfo(product:PackagedProduct, ifInCurrrentShelf:Long, ifMovedToTarget:Long, timeInMillisTillPickup:(Long,Long), shelf:Shelf) {
  def willExpireForSure(millisIntoFuture:Long):Boolean = ((ifInCurrrentShelf - millisIntoFuture) < timeInMillisTillPickup._1  && (ifMovedToTarget - millisIntoFuture) < timeInMillisTillPickup._1)

}
case class ProductPair(productInOverflow:ExpirationInfo, productInTarget:ExpirationInfo) {

  def willExpireForSure(millisIntoFuture:Long):Option[(Shelf,PackagedProduct)] = {
    if (productInOverflow.willExpireForSure(millisIntoFuture)) Some((overflow, productInOverflow.product))
    else if (productInTarget.willExpireForSure(millisIntoFuture)) Some(target,productInTarget.product)
    else None
  }
  def recommendedToExchange(threshold:Int):Boolean =
    willExpireForSure(threshold).isEmpty &&
      (productInOverflow.ifMovedToTarget + productInTarget.ifMovedToTarget) > (productInOverflow.ifInCurrrentShelf + productInTarget.ifInCurrrentShelf) &&
      target.canAccept(productInOverflow.product.order) && overflow.canAccept(productInTarget.product.order)

  def inCriticalZone(threshold:Int):Boolean
    = productInOverflow.willExpireForSure(threshold) || productInTarget.willExpireForSure(threshold)

  def overflow:Shelf = productInOverflow.shelf
  def target:Shelf = productInTarget.shelf
}