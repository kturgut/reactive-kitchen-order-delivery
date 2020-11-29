package reactive.storage

import java.time.{LocalDateTime, ZoneId}

import akka.event.LoggingAdapter
import reactive.config.ShelfConfig
import reactive.order.Temperature.{All, Cold, Frozen, Hot}
import reactive.order.{Order, Temperature}
import reactive.storage.ShelfManager.{DiscardOrder, ExpiredShelfLife}

import scala.collection.mutable


private[storage] case class Shelf(name: String, supports: Seq[Temperature],
                                  capacity: Int = 10,
                                  decayModifier: Int = 1,
                                  var products: mutable.SortedSet[PackagedProduct] = mutable.SortedSet[PackagedProduct]()(PackagedProduct.IncreasingValue)) {

  import Shelf._


  def snapshot():Shelf = copy(products = products.clone())

  def size: Int = products.size

  def hasAvailableSpace: Boolean = products.size < capacity

  def isOverCapacity: Boolean = products.size > capacity

  def availableSpace:Int = capacity - products.size

  def highestValueProduct: PackagedProduct = products.last

  def lowestValueProduct: PackagedProduct = products.head

  def capacityUtilization:Float =  products.size.toFloat / capacity

  def +=(elem: PackagedProduct): Shelf = {
    assert(canAccept(elem.order), s"Shelf $name cannot accept products that are ${elem.order.temp}")
    products += elem;
    this
  }

  def canAccept(order: Order): Boolean = supports.contains(order.temperature)

  def -=(elem: PackagedProduct): Shelf = {
    products -= elem;
    this
  }

  def getPackageForOrder(order: Order): Option[PackagedProduct] = products.find(product => product.order.id == order.id)

  def expireEndOfLifeProducts(time: LocalDateTime): Seq[DiscardOrder] = {
    val discarded = this.products.filter(product => product.phantomCopy(decayModifier, time).value < 0).map(product => DiscardOrder(product.order, ExpiredShelfLife, time))
    val discardedOrders = discarded.map(_.order)
    products = this.products.filterNot(p => discardedOrders.contains(p.order))
    discarded.toSeq
  }

  def productsDecreasingByOrderDate: List[PackagedProduct] = products.toList.sortBy(_.order.createdOn)(localDateTimeOrdering).reverse

  def reportContents(buffer:StringBuffer, verbose: Boolean = false): Unit = {
    buffer.append(s"\n$name shelf capacity utilization: ${products.size}/$capacity, decay rate modifier:$decayModifier.")
    if (verbose)
      buffer.append(s"\n       contents: ${products.map(product => (s"id:${product.order.id}",product.order.name, product.value)).mkString(",")}")
    else
      buffer.append(s"\n       contents: ${products.map(product => (s"id:${product.order.id}", s"value:${product.value}")).mkString(",")}")
  }

  def refresh(time: LocalDateTime): Shelf = {
    products = products.map(_.phantomCopy(decayModifier, time))
    this
  }
}

private[storage] case object Shelf {

  implicit val localDateTimeOrdering: Ordering[LocalDateTime] = Ordering.by(x => x.atZone(ZoneId.of("UTC")).toEpochSecond)

  def temperatureSensitiveShelves(config: ShelfConfig): mutable.Map[Temperature, Shelf] =
    mutable.Map(All -> overflow(config.OverflowShelfCapacity, config.OverflowShelfDecayModifier),
      Hot -> hot(config.HotShelfCapacity, config.HotShelfDecayModifier),
      Cold -> cold(config.ColdShelfCapacity, config.ColdShelfDecayModifier),
      Frozen -> frozen(config.FrozenShelfCapacity, config.FrozenShelfDecayModifier))

  def hot(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Hot", Hot :: Nil, capacity, decayModifier)

  def cold(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Cold", Cold :: Nil, capacity, decayModifier)

  def frozen(capacity: Int = 10, decayModifier: Int = 1) = Shelf("Frozen", Frozen :: Nil, capacity, decayModifier)

  def overflow(capacity: Int = 15, decayModifier: Int = 2) = Shelf("Overflow", All :: Hot :: Cold :: Frozen :: Nil, capacity, decayModifier)
}
