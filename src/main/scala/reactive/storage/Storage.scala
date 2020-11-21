package reactive.storage

import java.time.LocalDateTime

import akka.event.LoggingAdapter
import reactive.order.Temperature.All
import reactive.order.{Order, Temperature}
import reactive.storage.ShelfManager.{
  DiscardOrder,
  ExpiredShelfLife,
  ShelfCapacityExceeded
}

import scala.collection.mutable

private[storage] case class Storage(
  log: LoggingAdapter,
  shelves: mutable.Map[Temperature, Shelf] = Shelf.temperatureSensitiveShelves,
  createdOn: LocalDateTime = LocalDateTime.now()
) {

  assert(shelves.contains(All), "Overflow shelf not registered")
  assert(
    tempSensitiveShelves.values.forall(
      _.decayModifier <= overflow.decayModifier
    ),
    "Overflow shelf decayRate modifier is assumed to be higher or equal to other shelves'"
  )

  lazy val totalCapacity = shelves.map(_._2.capacity).sum
  private lazy val overflow: Shelf = shelves(All)
  private lazy val tempSensitiveShelves: mutable.Map[Temperature, Shelf] = {
    shelves.clone() -= All
  }
  val CriticalTimeThresholdForSwappingInMillis = 2000

  def isOverflowFull() = !hasAvailableSpaceFor(Temperature.All)

  def hasAvailableSpaceFor(temperature: Temperature) =
    shelves.values.exists(shelf =>
      shelf.supports.contains(temperature) && shelf.hasAvailableSpace
    )

  /** Put packaged product on shelf.
    * Initially the incoming package is put into overflow, and then overflow contents are pushed up as needed as part of
    * shelf optimization.
    * Return orders for existing packages to be dropped due to expiration or lack of space.
    *
    * @param product
    * @param time
    * @return
    */

  def putPackageOnShelf(
    product: PackagedProduct,
    time: LocalDateTime = LocalDateTime.now()
  ): Iterable[DiscardOrder] = {
    overflow.products += product
    optimizeShelfPlacement(time)
  }

  /** Since each shelf has different decayRateModifier constant and capacity, packages on shelves are shuffled to extend
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
  def optimizeShelfPlacement(
    time: LocalDateTime = LocalDateTime.now()
  ): Iterable[DiscardOrder] = {
    refresh(time)
    shelves.values.flatMap(
      _.expireEndOfLifeProducts(time)
    ) ++ optimizeOverflowShelf(time)
  }

  /** 1- Move products in overflow to the corresponding "temperature sensitive" shelves with better decayRate while they
    * have available capacity. These "temperature sensitive" shelves are labeled as "target" shelf in code.
    *
    * 2- If overflow shelf is full:
    *
    * a) Create pairings of products in overflow shelf with products with lowest remaining "value".
    * This pairing represents the likely product that would come down to overflow shelf if the product in overflow is
    * pushed to target shelf.
    * Value of a product is calculated by dividing "remainingShelfLife" of a product to original "shelfLife"
    * Note that as products are moved around in storage, a copy of the product is created with updated
    * "remainingShelfLife" and "value".
    *
    * b) Discard products that will expire before their earliest possible pickup time using the pairings created in 2-a
    * This is calculated using the assumption that there is a 2 second minimum delay between creation of an order
    * and the earliest expected time it will be picked up.
    * Note: Based on current order data, this may be considered an edge case, however in production systems there could
    * be delays in communication between StorageManager, and Courier, or Kitchen etc due to partitioning of nodes etc.
    * Also though not supported now, in real life couriers may actually get delayed for traffic or other reasons,
    * or a courier may be cancelled and another might be assigned by CourierManager. In such scenarios the expected
    * time of courier pickup may be updated. Since we are keeping track of OrderLifeCycle, we can query
    * OrderProcessor for an updated time of arrival or talk to the Courier directly rather than using a constant TODO
    *
    * c) Move products in "critical time zone" in overflow shelf to target shelves, essentially swapping them
    * with the lowest "value" product in target, if such swap would extend the total shelf life of the product in critical
    * zone without putting the other product in critical zone.
    * Critical zone threshold is currently set as constant: 2 seconds. Best value for such threshold TBD.
    * Why not keep higher threshold? To reduce the amount of swapping unless necessary for practical reasons.
    *
    * d) While overflow is over capacity: discard products with the newest order timestamp.
    * This is is to ensure customers are notified as soon as possible when an order is not going to get delivered,
    * and also reduce unnecessary processing in Kitchen and by Couriers.
    *
    * @param time
    * @return
    */
  private def optimizeOverflowShelf(time: LocalDateTime): List[DiscardOrder] = {
    var discarded: List[DiscardOrder] = Nil
    moveProductsToEmptySpacesInTemperatureSensitiveShelves(time)
    if (overflow.overCapacity) {
      var productPairs: List[ProductPair] = pairProductsForPotentialSwap()
      discarded = discardProductsThatWillExpireBeforeEarliestPossiblePickup(
        productPairs,
        time
      )
      productPairs = swapPairsOfProductsInCriticalZone(
        productPairs,
        time,
        CriticalTimeThresholdForSwappingInMillis
      )
      while (overflow.overCapacity) {
        discarded = discardDueToOverCapacity(
          overflow.productsDecreasingByOrderDate.head,
          overflow,
          time
        ) :: discarded
      }
    }
    discarded
  }

  private def pairProductsForPotentialSwap(): List[ProductPair] =
    overflow.products
      .groupBy(_.order.temperature)
      .collect { case (temperature, productsInGroup) =>
        val target = shelves(temperature)
        val p1 = productsInGroup.head
        val p2 = target.lowestValueProduct
        ProductPair(p1, overflow, p2, target)
      }
      .toList

  private def discardProductsThatWillExpireBeforeEarliestPossiblePickup(
    pairedProducts: List[ProductPair],
    time: LocalDateTime
  ): List[DiscardOrder] =
    for (x <- pairedProducts.flatMap(_.willExpireForSure(0))) yield {
      discardDueToOverCapacity(x._2, x._1, time)
    }

  private def discardDueToOverCapacity(
    product: PackagedProduct,
    shelf: Shelf,
    time: LocalDateTime
  ): DiscardOrder = {
    shelf.products -= product
    DiscardOrder(product.order, ShelfCapacityExceeded, time)
  }

  private def moveProductsToEmptySpacesInTemperatureSensitiveShelves(
    time: LocalDateTime
  ): Unit =
    tempSensitiveShelves.values
      .map(target =>
        (target, target.supports.head, target.capacity - target.products.size)
      )
      .foreach { case (target, temp, remainingCapacity) =>
        overflow.products
          .filter(_.order.temperature == temp)
          .take(remainingCapacity)
          .foreach { product =>
            moveProductToTargetShelfMaintainingCapacity(
              overflow,
              product,
              target,
              time
            )
          }
      }

  private def moveProductToTargetShelfMaintainingCapacity(
    source: Shelf,
    product: PackagedProduct,
    target: Shelf,
    time: LocalDateTime
  ): Option[PackagedProduct] = {
    val updatedProduct = product.phantomCopy(source.decayModifier.toFloat, time)
    log.debug(
      s"Moving ${product.prettyString} from ${source.name} to ${target.name}  "
    )
    target.products += updatedProduct
    source.products -= product
    if (target.overCapacity) {
      val productToRemove = target.lowestValueProduct
      target.products -= productToRemove
      Some(productToRemove.phantomCopy(target.decayModifier.toFloat, time))
    } else
      None
  }

  private def swapPairsOfProductsInCriticalZone(
    pairedProducts: List[ProductPair],
    time: LocalDateTime,
    criticalZoneThreshold: Int = CriticalTimeThresholdForSwappingInMillis
  ): List[ProductPair] = {
    for (
      pair <- pairedProducts.filter(pair =>
        pair.inCriticalZone(criticalZoneThreshold) && pair
          .swapRecommended(criticalZoneThreshold)
      )
    ) yield {
      moveProductToTargetShelfMaintainingCapacity(
        pair.overflow,
        pair.inOverflow.product,
        pair.target,
        time
      ) match {
        case Some(productOnShelf) =>
          pair.overflow += productOnShelf.phantomCopy(
            overflow.decayModifier.toFloat,
            time
          )
        case None =>
      }
    }
    pairProductsForPotentialSwap()
  }

  private def refresh(time: LocalDateTime = LocalDateTime.now()): Unit =
    shelves.values.foreach(_.refresh(time))

  /** Pick up packaged product for an order.
    * Returns "Either" found package option on the left, or a discarded order on the right
    * If product is not found it will be returned as "None" on the Either.left
    *
    * @param order
    * @return
    */
  def pickupPackageForOrder(
    order: Order,
    time: LocalDateTime = LocalDateTime.now()
  ): Either[Option[PackagedProduct], DiscardOrder] = {
    refresh(time)
    fetchPackageForOrder(order) match {
      case Some(packagedProduct) =>
        if (packagedProduct.value > 0)
          Left(Some(packagedProduct))
        else
          Right(
            DiscardOrder(
              packagedProduct.order,
              ExpiredShelfLife,
              packagedProduct.updatedOn
            )
          )
      case None => Left(None)
    }
  }

  private[storage] def fetchPackageForOrder(
    order: Order
  ): Option[PackagedProduct] = shelves.values
    .flatMap { shelf =>
      val packageOption = shelf.getPackageForOrder(order)
      packageOption match {
        case Some(product) => shelf -= product; Some(product)
        case _             => None
      }
    }
    .toList
    .headOption

  /** create a snapshot copy in time.
    */
  def snapshot(time: LocalDateTime = LocalDateTime.now()): Storage = {
    refresh(time)
    copy(log, shelves.clone(), time)
  }

  def reportStatus(
    verbose: Boolean = false,
    time: LocalDateTime = LocalDateTime.now()
  ): Unit = {
    refresh(time)
    log.info(
      s">> Storage capacity utilization: ${this.totalProductsOnShelves}/${this.totalCapacity}, last refreshed:$createdOn"
    )
    if (verbose) {
      shelves.values.foreach(_.reportContents(log, verbose))
    } else {
      overflow.products.groupBy(_.order.temperature).foreach {
        case (temperature, productsInGroup) =>
          log.debug(s"   $temperature -> $productsInGroup")
          shelves.values.foreach(_.reportContents(log, verbose))
      }
    }
  }

  def totalProductsOnShelves: Int = shelves.values.map(_.size).sum

}
