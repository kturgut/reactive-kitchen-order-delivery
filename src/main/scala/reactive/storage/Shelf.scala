package reactive.storage

import java.time.{LocalDateTime, ZoneId}

import akka.event.LoggingAdapter
import reactive.order.Temperature.{All, Cold, Frozen, Hot}
import reactive.order.{Order, Temperature}
import reactive.storage.ShelfManager.{DiscardOrder, ExpiredShelfLife}

import scala.collection.mutable

private[storage] case class Shelf(
  name: String,
  supports: Seq[Temperature],
  capacity: Int = 10,
  decayModifier: Int = 1,
  var products: mutable.SortedSet[PackagedProduct] =
    mutable.SortedSet[PackagedProduct]()(PackagedProduct.IncreasingValue)
) {

  import Shelf._

  def size: Int = products.size

  def hasAvailableSpace: Boolean = products.size < capacity

  def overCapacity: Boolean = products.size > capacity

  def highestValueProduct: PackagedProduct = products.last

  def lowestValueProduct: PackagedProduct = products.head

  def +=(elem: PackagedProduct): Shelf = {
    assert(
      canAccept(elem.order),
      s"Shelf $name cannot accept products that are ${elem.order.temp}"
    )
    products += elem;
    this
  }

  def canAccept(order: Order): Boolean = supports.contains(order.temperature)

  def -=(elem: PackagedProduct): Shelf = {
    products -= elem;
    this
  }

  def getPackageForOrder(order: Order): Option[PackagedProduct] =
    products.find(product => product.order.id == order.id)

  def expireEndOfLifeProducts(time: LocalDateTime): Seq[DiscardOrder] = {
    val discarded = this.products
      .filter(product =>
        product.phantomCopy(decayModifier.toFloat, time).value < 0
      )
      .unsorted
      .map(product => DiscardOrder(product.order, ExpiredShelfLife, time))
    val discardedOrders = discarded.map(_.order)
    products = this.products.filterNot(p => discardedOrders.contains(p.order))
    discarded.toSeq
  }

  def productsDecreasingByOrderDate: List[PackagedProduct] = products.toList
    .sortBy(_.order.createdOn)(localDateTimeOrdering)
    .reverse // TODO fix ordering to reverse

  def reportContents(log: LoggingAdapter, verbose: Boolean = false): Unit = {
    log.info(
      s"$name shelf capacity utilization:${products.size} / $capacity, decay rate modifier:$decayModifier."
    )
    if (verbose)
      log.info(
        s"       contents: ${products.map(product => (product.order.name, product.value)).mkString(",")}"
      )
  }

  def refresh(time: LocalDateTime): Shelf = {
    products = products.map(_.phantomCopy(decayModifier.toFloat, time))
    this
  }

}

private[storage] case object Shelf {

  implicit val localDateTimeOrdering: Ordering[LocalDateTime] =
    Ordering.by(x => x.atZone(ZoneId.of("UTC")).toEpochSecond)

  def temperatureSensitiveShelves: mutable.Map[Temperature, Shelf] =
    mutable.Map(
      All -> overflow(),
      Hot -> hot(),
      Cold -> cold(),
      Frozen -> frozen()
    )

  def hot(capacity: Int = 10, decayModifier: Int = 1) =
    Shelf("Hot", Hot :: Nil, capacity, decayModifier)

  def cold(capacity: Int = 10, decayModifier: Int = 1) =
    Shelf("Cold", Cold :: Nil, capacity, decayModifier)

  def frozen(capacity: Int = 10, decayModifier: Int = 1) =
    Shelf("Frozen", Frozen :: Nil, capacity, decayModifier)

  def overflow(capacity: Int = 15, decayModifier: Int = 2) = Shelf(
    "Overflow",
    All :: Hot :: Cold :: Frozen :: Nil,
    capacity,
    decayModifier
  )
}
