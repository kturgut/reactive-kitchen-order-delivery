package cloudkitchens.kitchen

import java.time.LocalDateTime

import akka.event.LoggingAdapter
import cloudkitchens.kitchen.ShelfManager.{DiscardOrder, ExpiredShelfLife, ShelfCapacityExceeded}
import cloudkitchens.order.{Order, Temperature}
import cloudkitchens.order.Temperature.All

import scala.collection.mutable

/**
 * Storage is the Internal data structure of Shelf Manager that maintains the Shelves.
 *
 * Objective
 * Storage Product Life Cycle Optimization Algorithm has the following objectives:
 * 1- Minimize the number of discarded products due to expiration
 * 2- Minimize the time to discard an order if an order has to be discarded due to insufficient shelf capacity
 * 3- Maximize the shelfLife 'value' of the products delivered to customers
 *
 * Background
 * 1- Shelves keep the products in increasing Order of "value"
 * 2- Storage can be configured to work with multiple shelves each of which provide different Capacity, decayRate
 * 3- Each Shelve can declare what "temperatures" it supports.
 * 4- 'Overflow' shelf supports all temperatures but has a higher decayRate (default = 2)
 * 5- 'Hot', 'Frozen', 'Cold' shelves only accept products that matches the corresponding temperatures.
 * 6- All new products received are put into Overflow shelf first.
 * 7- Shelf optimization is done a) when new products are added to Storage b) periodically on schedule (currently every second)
 * 7- Couriers are expected to arrive between 2 to 6 seconds after they receive assignment for an order.

 *
 * Overflow Shelf Optimization
 * 1- Remove expired products on all shelves
 * 3- Move products in overflow shelf to corresponding 'temperature sensitive' (aka target) shelves with lower decayRate modifier
 * while there is capacity on those shelves
 * Note: Current implementation assumes single "target" shelf for each temperature
 * 3- If overflow shelf is full
 *   a) Expire products that will not be be deliverable based on minimum expected pickup time (using the 2 second minimum delay)
 * ShelfOptimization
 *   b) Replace products that can benefit with increased shelf life if swapped by a product in target shelf.
 *   This is controlled by CRITICAL_TIME_THRESHOLD
 *   c) While overflow is over capacity: discard the product with the newest order creation timestamp.
 */

private [kitchen] case class Storage(log: LoggingAdapter, shelves: mutable.Map[Temperature, Shelf] = Shelf.temperatureSensitiveShelves) {

  assert(shelves.contains(All), "Overflow shelf not registered")
  assert(tempSensitiveShelves.values.forall(_.decayModifier<=overflow.decayModifier),
    "Overflow shelf decayRate modifier is assumed to be higher or equal to other shelves'")

  private lazy val overflow: Shelf = shelves(All)
  private lazy val tempSensitiveShelves: mutable.Map[Temperature, Shelf] = shelves - All

  def totalProductsOnShelves:Int = shelves.values.map(_.size).sum
  lazy val totalCapacity= shelves.map(_._2.capacity).sum
  def hasAvailableSpace: Boolean = shelves.values.find(_.hasAvailableSpace).isDefined

  val CriticalTimeThresholdForSwappingInMillis = 2000

  /**
   * Put packaged product on shelf.
   * Initially the incoming package is put into overflow, and then overflow contents are pushed up as needed as part of
   * shelf optimization.
   * Return orders for existing packages to be dropped due to expiration or lack of space.
   * @param product
   * @param time
   * @return
   */

  def putPackageOnShelf(product: PackagedProduct, time: LocalDateTime = LocalDateTime.now()): Iterable[DiscardOrder] = {
    overflow.products += product
    optimizeShelfPlacement(time)
  }


  /**
   * Pick up packaged product for an order.
   * Returns "Either" found package option on the left, or a discarded order on the right
   * If product is not found it will be returned as "None" on the Either.left
   * @param order
   * @return
   */
  def pickupPackageForOrder(order:Order):Either[Option[PackagedProduct], DiscardOrder] =
    fetchPackageForOrder(order) match {
      case Some(packagedProduct) =>
        if (packagedProduct.value > 0)
          Left(Some(packagedProduct))
        else Right(DiscardOrder(packagedProduct.order, ExpiredShelfLife, packagedProduct.updatedOn))
      case None => Left(None)
    }

  /**
   * Since each shelf has different decayRateModifier constant and capacity, packages on shelves are shuffled to extend
   * their shelf life for optimum placement.
   * Optimization is done in two steps
   * 1- expire packages who has reached their end of life on all shelves
   * 2- optimize the contents of the overflow shelves moving them to other shelves as needed
   * Both (1) and (2) may produce packages dropped from shelves. Return those as list of DiscardOrder.
   *
   * Note that this method is called
   * a) when new products are added to the storage
   * b) periodically on a schedule set by StorageManager
   *
   * @param time
   * @return
   */
  def optimizeShelfPlacement(time: LocalDateTime = LocalDateTime.now()): Iterable[DiscardOrder] =
    shelves.values.flatMap(_.expireEndOfLifeProducts(time)) ++ optimizeOverflowShelf(time)



  private [kitchen] def fetchPackageForOrder(order: Order): Option[PackagedProduct] = shelves.values.flatMap{shelf =>
    val packageOption = shelf.getPackageForOrder(order)
    packageOption match {
      case Some(product) => shelf -= product; Some(product.phantomCopy(shelf.decayModifier))
      case _ => None
    }}.toList.headOption


  /**
   * 1- Move products in overflow to the corresponding "temperature sensitive" shelves with better decayRate while they
   * have available capacity. These "temperature sensitive" shelves are labeled as "target" shelf in code.
   *
   * 2- If overflow shelf is full:
   *
   *    a) Create pairings of products in overflow shelf with products with lowest remaining "value".
   *    This pairing represents the likely product that would come down to overflow shelf if the product in overflow is
   *    pushed to target shelf.
   *    Value of a product is calculated by dividing "remainingShelfLife" of a product to original "shelfLife"
   *    Note that as products are moved around in storage, a copy of the product is created with updated
   *    "remainingShelfLife" and "value".
   *
   *    b) Discard products that will expire before their earliest possible pickup time using the pairings created in 2-a
   *    This is calculated using the assumption that there is a 2 second minimum delay between creation of an order
   *    and the earliest expected time it will be picked up. Based on current order data, this is an edge case which may
   *    happen due to delays in communication between StorageManager, and Courier, or Kitchen.
   *    TODO: use the courierAssignment maintained in StorageManager for expected arrival time of courier for better accuracy
   *    Also though not supported now, in real life couriers may actually get delayed for traffic or other reasons,
   *    or a courier may be cancelled and another might be assigned by CourierManager. In such scenarios the expected
   *    time of courier pickup may be updated.
   *
   *    c) Move products in "critical time zone" in overflow shelf to target shelves, essentially swapping them
   *    with the lowest "value" product in target, if such swap would extend the total shelf life of those products.
   *    Critical zone threshold is currently set as constant: 2 seconds. Best value for such threshold TBD.
   *    Why not keep higher threshold? To reduce the amount of swapping unless necessary for practical reasons.
   *
   *    d) While overflow is over capacity: discard products with the newest order timestamp.
   *    This is is to ensure customers are notified as soon as possible when an order is not going to get delivered,
   *    and also reduce unnecessary processing in Kitchen and by Couriers.
   *
   * @param time
   * @return
   */
  private def optimizeOverflowShelf(time: LocalDateTime): List[DiscardOrder] = {
    var discarded: List[DiscardOrder] = Nil
    moveProductsToEmptySpacesInTemperatureSensitiveShelves(time)
    if (overflow.overCapacity) {
      var productPairs:List[ProductPair] = pairProductsForPotentialSwap()
      discarded = discardProductsThatWillExpireBeforeEarliestPossiblePickup(productPairs, time)
      productPairs = swapPairsOfProductsInCriticalZone(productPairs, time, CriticalTimeThresholdForSwappingInMillis)
      while (overflow.overCapacity) {
        discarded = discardDueToOverCapacity(overflow.productsOrderedByOrderDate.head, overflow, time) :: discarded
      }
    }
    discarded
  }

  private def pairProductsForPotentialSwap():List[ProductPair] = overflow.products.groupBy(_.order.temperature).collect {
    case (temperature, productsInGroup) =>
      val target = shelves(temperature)
      val overflowDecayModifier = overflow.decayModifier
      val targetDecayModifier = target.decayModifier
      val p1 = productsInGroup.head
      val p2 = target.lowestValueProduct
      ProductPair(
        ExpirationInfo(p1, p1.expirationInMillis(overflowDecayModifier), p1.expirationInMillis(targetDecayModifier), p1.minimalWaitTimeForPickup(),overflow),
        ExpirationInfo(p2, p2.expirationInMillis(targetDecayModifier), p2.expirationInMillis(overflowDecayModifier), p2.minimalWaitTimeForPickup(),target),
      )
  }.toList

  private def discardProductsThatWillExpireBeforeEarliestPossiblePickup(pairedProducts: List[ProductPair], time:LocalDateTime):List[DiscardOrder] =
    for (x <- pairedProducts.flatMap(_.willExpireForSure(0))) yield {discardDueToOverCapacity(x._2,x._1,time)}


  private def discardDueToOverCapacity(product: PackagedProduct, shelf: Shelf, time: LocalDateTime): DiscardOrder = {
    shelf.products -= product
    DiscardOrder(product.order, ShelfCapacityExceeded, time)
  }

  private def moveProductsToEmptySpacesInTemperatureSensitiveShelves(time: LocalDateTime): Unit =
    tempSensitiveShelves.values.map(target => (target, target.supports.head, target.capacity - target.products.size)).map {
      case (target, temp, remainingCapacity) =>
        overflow.products.filter(_.order.temperature == temp).take(remainingCapacity).foreach {
          case product => moveProductToTargetShelfMaintainingCapacity(overflow, product, target, time)
        }
    }

  private def swapPairsOfProductsInCriticalZone(pairedProducts: List[ProductPair],
                                        time:LocalDateTime,
                                        criticalZoneThreshold:Int = CriticalTimeThresholdForSwappingInMillis):List[ProductPair] = {
    for (pair <- pairedProducts.filter(pair =>
      pair.inCriticalZone(criticalZoneThreshold) && pair.recommendedToExchange(criticalZoneThreshold))) yield {
      moveProductToTargetShelfMaintainingCapacity(pair.overflow, pair.productInOverflow.product, pair.target, time) match {
        case Some(productOnShelf) => pair.overflow += productOnShelf.phantomCopy(overflow.decayModifier, time)
        case None =>
      }
    }
    pairProductsForPotentialSwap()
  }

  private def moveProductToTargetShelfMaintainingCapacity(source: Shelf,
                                                  product: PackagedProduct, target: Shelf, time: LocalDateTime): Option[PackagedProduct] = {
    val updatedProduct = product.phantomCopy(source.decayModifier, time)
    log.debug(s"Moving ${product.prettyString} from ${source.name} to ${target.name}  ")
    target.products += updatedProduct
    source.products -= product
    if (target.overCapacity) {
      val productToRemove = target.lowestValueProduct
      target.products -= productToRemove
      Some(productToRemove.phantomCopy(target.decayModifier, time))
    } else
      None
  }

  def reportStatus(verbose:Boolean=false): Unit = {
    log.info(s">> Storage capacity utilization: ${this.totalProductsOnShelves}/${this.totalCapacity} ")
    if (verbose) {
      shelves.values.foreach(_.reportContents(log,verbose))
    }
    else {
      overflow.products.groupBy(_.order.temperature).foreach {
        case (temperature, productsInGroup) =>
          log.debug(s"   $temperature -> $productsInGroup")
          shelves.values.foreach(_.reportContents(log,verbose))
      }
    }
  }

}