package cloudkitchens.kitchen

import java.time.LocalDateTime

import cloudkitchens.order.{Order, Temperature}
import cloudkitchens.order.Temperature.{All, Cold, Frozen, Hot}

import scala.collection.mutable


sealed case class Shelf(supports:Seq[Temperature],
                   capacity:Int=10,
                   decayModifier:Int=1,
                   products:mutable.SortedSet[Product] = mutable.SortedSet[Product]()(Product.IncreasingValue))  {

  def peekHighestValue:Product = products.last
  def peekLowestValue:Product = products.head

  def + (elem: Product): Shelf = {products += elem; this}
  def - (elem: Product): Shelf = {products -= elem; this}

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

case class KitchenShelves (shelves: mutable.Map[Temperature, Shelf]=Shelf.temperatureSensitiveShelves) {
  assert(shelves.contains(All), "Overflow shelf not registered")

  def overflow = shelves(All)
  def tempSensitiveShelves = shelves - All

  def createProductOnShelf(order:Order, time:LocalDateTime = LocalDateTime.now()): Product ={
    shelves.values.foreach(_.expireOldProducts(time))
    val product = Product(order, time)
    overflow.products+=product
    optimizeOverflowShelf(overflow,time)
    product
  }

  def moveProductTo(source:Shelf, product:Product, target:Shelf, time:LocalDateTime):Option[Product] = {
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

  def discardDueToOverCapacity(product:Product, shelf:Shelf, time:LocalDateTime) = {
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