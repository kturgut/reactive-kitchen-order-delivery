package cloudkitchens.kitchen

import java.time.LocalDateTime

import akka.event.LoggingAdapter
import cloudkitchens.order.{Order, Temperature}
import cloudkitchens.order.Temperature.{All, Cold, Frozen, Hot}

import scala.collection.mutable


sealed case class Shelf(supports:Seq[Temperature],
                   capacity:Int=10,
                   decayModifier:Int=1,
                   products:mutable.SortedSet[PackagedProduct] = mutable.SortedSet[PackagedProduct]()(PackagedProduct.IncreasingValue))  {

  def peekHighestValue:PackagedProduct = products.last
  def peekLowestValue:PackagedProduct = products.head

  def + (elem: PackagedProduct): Shelf = {products += elem; this}
  def - (elem: PackagedProduct): Shelf = {products -= elem; this}

  def get(order:Order):Option[PackagedProduct] = products.find(_.order.id == order.id)

  def expireOldProducts(time:LocalDateTime):Shelf = {
    this.copy(products = this.products.filter(product=> product.updatedCopy(this, time).value > 0))
    this
  }

  def canAccept(order:Order):Boolean = supports.contains(order.temperature)
  def hasRoom:Boolean = products.size < capacity
  def overCapacity:Boolean = products.size > capacity
  def size = products.size
}

case object Shelf {
  def hot(capacity: Int = 10, decayModifier: Int = 1) = Shelf(Hot :: Nil, capacity, decayModifier)
  def cold(capacity: Int = 10, decayModifier: Int = 1) = Shelf(Cold :: Nil, capacity, decayModifier)
  def frozen(capacity: Int = 10, decayModifier: Int = 1) = Shelf(Frozen :: Nil, capacity, decayModifier)
  def overflow(capacity: Int = 15, decayModifier: Int = 2) = Shelf(All :: Hot :: Cold :: Frozen :: Nil, capacity, decayModifier)
  def temperatureSensitiveShelves: mutable.Map[Temperature, Shelf] =
    mutable.Map(All-> overflow(), Hot -> hot(), Cold -> cold(), Frozen -> frozen())
}

case class KitchenShelves (log:LoggingAdapter, shelves: mutable.Map[Temperature, Shelf]=Shelf.temperatureSensitiveShelves) {
  assert(shelves.contains(All), "Overflow shelf not registered")

  def overflow = shelves(All)
  def tempSensitiveShelves = shelves - All

  def putOnShelf(product:PackagedProduct, time:LocalDateTime = LocalDateTime.now()): PackagedProduct ={
    shelves.values.foreach(_.expireOldProducts(time))
    overflow.products+=product
    optimizeOverflowShelf(overflow,time)
    product
  }

  def getPackageForOrder(order: Order) =
    shelves.values.flatMap(shelf=>shelf.get(order) match {
      case product:PackagedProduct => shelf - product; Some(product)
      case _ => None
    }).flatten.toList.headOption


  def moveProductTo(source:Shelf, product:PackagedProduct, target:Shelf, time:LocalDateTime):Option[PackagedProduct] = {
    target.products += product.updatedCopy(source,time)
    source.products -=product
    if (target.overCapacity) {
      val productToRemove = target.peekLowestValue
      target.products-=productToRemove
      Some(productToRemove.updatedCopy(target,time))
    } else
      None
  }

  private def moveProductsToEmptySpacesInTemperatureSensitiveShelves(time:LocalDateTime):Unit =
    tempSensitiveShelves.values.map(target=> (target, target.supports.head, target.capacity - target.products.size)).map {
      case (target, temp, remainingCapacity) =>
        overflow.products.filter(_.order.temperature == temp).take(remainingCapacity).foreach {
          case product => moveProductTo(overflow, product, target, time)
        }
    }

  def discardDueToOverCapacity(product:PackagedProduct, shelf:Shelf, time:LocalDateTime) = {
    println(s"Discarding product $product since shelf capacity is full")
    shelf.products -= product
  }

  private def optimizeOverflowShelf(shelf:Shelf, time:LocalDateTime) = {
    moveProductsToEmptySpacesInTemperatureSensitiveShelves(time)
    // TODO optimize items in overflow by moving them to upper shelves as needed before overflow gets full.
    var secondsIntoFuture: Int = 0
    while (shelf.size > shelf.capacity && secondsIntoFuture < 6) {
      secondsIntoFuture += 1
      shelf.products.groupBy(_.order.temperature).foreach {
        case (temperature, productsInGroup) =>
          val target = shelves(temperature)
          val lowestValueProductInOverflow = productsInGroup.head.updatedCopy(shelf, time.plusSeconds(secondsIntoFuture))
          val lowestValueProductInTarget = target.peekLowestValue.updatedCopy(target, time.plusSeconds(secondsIntoFuture))
          if (lowestValueProductInOverflow.value < 0) {
            if (lowestValueProductInTarget.value > lowestValueProductInOverflow.value) {
              moveProductTo(shelf, shelf.peekLowestValue, target, time) match {
                case Some(productOnShelf) => discardDueToOverCapacity(productOnShelf, target,time)
              }
            }
            else  discardDueToOverCapacity(lowestValueProductInOverflow, shelf, time)
          }
      }
    }
  }
}