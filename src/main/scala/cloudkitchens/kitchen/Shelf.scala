package cloudkitchens.kitchen

import java.time.LocalDateTime

import akka.event.LoggingAdapter
import cloudkitchens.kitchen.ShelfManager.{DiscardOrder, ExpiredShelfLife}
import cloudkitchens.order.Temperature.{All, Cold, Frozen, Hot}
import cloudkitchens.order.{Order, Temperature}

import scala.collection.mutable


sealed case class Shelf(name: String, supports: Seq[Temperature],
                        capacity: Int = 10,
                        decayModifier: Int = 1,
                        var products: mutable.SortedSet[PackagedProduct] = mutable.SortedSet[PackagedProduct]()(PackagedProduct.IncreasingValue)) {

  def peekHighestValue: PackagedProduct = products.last

  def peekLowestValue: PackagedProduct = products.head

  def +(elem: PackagedProduct): Shelf = {
    products += elem; this
  }

  def -(elem: PackagedProduct): Shelf = {
    products -= elem; this
  }

  def get(order: Order): Option[PackagedProduct] = products.find(product => product.order.id == order.id)

  def expireEndOfLifeProducts(time: LocalDateTime): Seq[DiscardOrder] = {
    val discarded = this.products.filter(product => product.updatedCopy(this, time).value < 0).map(product => DiscardOrder(product.order, ExpiredShelfLife, time))
    val discardedOrders = discarded.map(_.order)
    products = this.products.filterNot(p => discardedOrders.contains(p.order))
    discarded.toSeq
  }

  def canAccept(order: Order): Boolean = supports.contains(order.temperature)

  def hasRoom: Boolean = products.size < capacity

  def overCapacity: Boolean = products.size > capacity

  def size = products.size
}

case object Shelf {
  def temperatureSensitiveShelves: mutable.Map[Temperature, Shelf] =
    mutable.Map(All -> overflow(), Hot -> hot(), Cold -> cold(), Frozen -> frozen())

  def hot(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Hot", Hot :: Nil, capacity, decayModifier)

  def cold(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Cold", Cold :: Nil, capacity, decayModifier)

  def frozen(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Frozen", Frozen :: Nil, capacity, decayModifier)

  def overflow(capacity: Int = 15, decayModifier: Int = 2) = Shelf("Overflow", All :: Hot :: Cold :: Frozen :: Nil, capacity, decayModifier)
}

case class KitchenShelves(log: LoggingAdapter, shelves: mutable.Map[Temperature, Shelf] = Shelf.temperatureSensitiveShelves) {
  assert(shelves.contains(All), "Overflow shelf not registered")

  def removePackageForOrder(order: Order): Option[PackagedProduct] = shelves.values.flatMap(shelf => shelf.get(order) match {
    case product: PackagedProduct =>
      shelf - product; Some(product)
    case _ => None
  }).flatten.toList.headOption

  def putPackageOnShelf(product: PackagedProduct, time: LocalDateTime = LocalDateTime.now()): Iterable[DiscardOrder] = {
    overflow.products += product
    optimizeShelfPlacement(time)
  }

  private def overflow: Shelf = shelves(All)

  def optimizeShelfPlacement(time: LocalDateTime = LocalDateTime.now()): Iterable[DiscardOrder] =
    shelves.values.flatMap(_.expireEndOfLifeProducts(time)) ++ optimizeOverflowShelf(time)

  private def optimizeOverflowShelf(time: LocalDateTime): List[DiscardOrder] = {
    moveProductsToEmptySpacesInTemperatureSensitiveShelves(time)
    var secondsIntoFuture: Int = 0
    // if all shelves are full, carefully pick one that is about to expire soonest to discard, minimizing number of discarded orders
    var discarded: List[DiscardOrder] = Nil
    while (overflow.size > overflow.capacity && secondsIntoFuture < 7) {
      secondsIntoFuture += 1
      overflow.products.groupBy(_.order.temperature).foreach {
        case (temperature, productsInGroup) =>
          val target = shelves(temperature)
          val lowestInTarget: PackagedProduct = target.peekLowestValue
          val lowestInOverflowInFuture = productsInGroup.head.updatedCopy(overflow, time.plusSeconds(secondsIntoFuture))
          val lowestInTargetInFuture = lowestInTarget.updatedCopy(target, time.plusSeconds(secondsIntoFuture))
          // switch products between overflow and target shelf and discard the one in target if (a) and (b) OR
          // discard lowest value product in overflow if only (a)
          // a) the lowestValue product's value in overflow will be negative if the future
          // b) the lowestValue product in target shelf is not going to expire in  min(4,secondsIntoFuture) seconds.
          if (lowestInOverflowInFuture.value < 0) {
            val lowestInTargetInFutureIfMovedToOverflow = lowestInTarget.updatedCopy(overflow, time.plusSeconds(math.min(secondsIntoFuture, 4)))
            if (lowestInTargetInFuture.value > lowestInOverflowInFuture.value && lowestInTargetInFutureIfMovedToOverflow.value > 0) {
              moveProductToTargetShelfMaintainingCapacity(overflow, overflow.peekLowestValue, target, time) match {
                case Some(productOnShelf) =>
                  discarded = discardDueToOverCapacity(productOnShelf, target, time) :: discarded
              }
            }
            else discarded = discardDueToOverCapacity(lowestInOverflowInFuture, overflow, time) :: discarded
          }
      }
    }
    discarded
  }

  private def moveProductsToEmptySpacesInTemperatureSensitiveShelves(time: LocalDateTime): Unit =
    tempSensitiveShelves.values.map(target => (target, target.supports.head, target.capacity - target.products.size)).map {
      case (target, temp, remainingCapacity) =>
        overflow.products.filter(_.order.temperature == temp).take(remainingCapacity).foreach {
          case product => moveProductToTargetShelfMaintainingCapacity(overflow, product, target, time)
        }
    }

  private def tempSensitiveShelves: mutable.Map[Temperature, Shelf] = shelves - All

  private def moveProductToTargetShelfMaintainingCapacity(source: Shelf,
                                                          product: PackagedProduct, target: Shelf, time: LocalDateTime): Option[PackagedProduct] = {
    val updatedProduct = product.updatedCopy(source, time)
    log.debug(s"Moving product ${product} from ${source.name} to ${target.name}  ")
    target.products += updatedProduct
    source.products -= product
    if (target.overCapacity) {
      val productToRemove = target.peekLowestValue
      target.products -= productToRemove
      Some(productToRemove.updatedCopy(target, time))
    } else
      None
  }

  private def discardDueToOverCapacity(product: PackagedProduct, shelf: Shelf, time: LocalDateTime): DiscardOrder = {
    println(s"Discarding product $product since shelf capacity is full")
    shelf.products -= product
    DiscardOrder(product.order, ShelfManager.ShelfCapacityExceeded, time)
  }

}