package reactive.storage

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import reactive.JacksonSerializable
import reactive.delivery.Courier.{CourierAssignment, Pickup, PickupRequest}
import reactive.kitchen.Kitchen.OverflowUtilizationSafetyThreshold
import reactive.order.{Order, Temperature}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt

/**
 * ShelfManager is a Stateless Actor. Parent Actor is Kitchen.
 *   Kitchen receives PackagedProducts from Kitchen and delivers them Courier that is assigned.
 *   Courier assigned for delivery sends a CourierAssignment to ShelfManager
 *   ShelfManager caches active CourierAssignments (FIFO cache with fixed size)
 *   When Couriers request Pickup if the PackagedProduct is still on the shelf it is returned, otherwise
 *   DiscardOrder is returned. ShelfManager has a cache that it tracks Courier Assignments.
 *
 * ShelfManager currently does not have a mechanism put backpressure to Kitchen. It is in active state after creation.
 *
 * Timers: ShelfManager has an recurring timer that triggers ManageProductsOnShelves every one second to automatically check
 *    the contents on the shelves and move them around or discard them as necessary
 *
 * ShelfManager handles the following incoming messages:
 *    PackagedProduct => Puts the incoming package into the Overflow shelf, and starts optimization of overflow shelf.
 *       Any DiscardedOrders are published to Courier that is assigned for the package as well as to OrderProcessor
 *       for OrderLifeCycleManagement
 *    PickupRequest => Send {Pickup | DiscardOrder | None} to Courier
 *       Check Storage for the product from Storage, if found return it, if not found return None.
 *       There is a small chance that the order may have expired just recently, if so a DiscardOrder notice might be returned
 *       to Courier instead.
 *       Note that if product expiration is detected outside of this pickup request, the courier will get a direct
 *       message about the expiration and this PickupRequest will not be received.
 */
object ShelfManager {

  val MaximumCourierAssignmentCacheSize = 100
  val ExpiredShelfLife = "ExpiredShelfLife"
  val ShelfCapacityExceeded = "ShelfCapacityExceeded"

  def props(kitchenOption: Option[ActorRef]=None, orderProcessorOption: Option[ActorRef]=None) =
    Props(new ShelfManager(kitchenOption, orderProcessorOption))

  // TODO Turn discard reason to trait
  case class DiscardOrder(order: Order, reason: String, createdOn: LocalDateTime) extends JacksonSerializable

  case object TimerKey

  case object StartAutomaticShelfLifeOptimization

  case object StopAutomaticShelfLifeOptimization

  case object ManageProductsOnShelves

  case object RequestCapacityUtilization
  case class  CapacityUtilization(overlow:Float, allShelves:Float)
}

class ShelfManager(kitchenOption: Option[ActorRef], orderProcessorOption: Option[ActorRef]) extends Actor with ActorLogging with Timers {

  import ShelfManager._

  timers.startSingleTimer(TimerKey, StartAutomaticShelfLifeOptimization, 100 millis)

  override def receive: Receive = readyForService(ListMap.empty, Storage(log))

  def readyForService(courierAssignments: ListMap[Order, CourierAssignment], storage: Storage): Receive = {

    case StartAutomaticShelfLifeOptimization =>
      timers.startTimerWithFixedDelay(TimerKey, ManageProductsOnShelves, 1 second)

    case StopAutomaticShelfLifeOptimization =>
      log.warning("Stopping Automatic Shelf Life Optimization")
      timers.cancel(TimerKey)

    case ManageProductsOnShelves =>
      val updatedAssignments = publishDiscardedOrders(storage.optimizeShelfPlacement(), courierAssignments)
      if (storage.capacityUtilization(Temperature.All) > OverflowUtilizationSafetyThreshold) {
        storage.reportStatus(true)
        kitchenOption.foreach(_ ! storage.capacityUtilization)
      }
      context.become(readyForService(updatedAssignments, storage.snapshot()))

    case product: PackagedProduct =>
      log.debug(s"Putting new product ${product.prettyString} on shelf")
      // TODO handle the case if a packagedProduct for the same order is already on the Shelf. Discard previous one?
      val updatedAssignments = publishDiscardedOrders(storage.putPackageOnShelf(product), courierAssignments)
      context.become(readyForService(updatedAssignments, storage.snapshot()))

    // If product found and not expired return it, else if expired return discard order instead and notify order processor
    case pickupRequest: PickupRequest =>
      log.debug(s"Shelf manager received pickup request ${pickupRequest.assignment.prettyString}")
      // TODO match the courier with the CourierAssignment. If not in courierAssignmentCache, it can query LifeCycleManage for an update to be safe!?!
      storage.pickupPackageForOrder(pickupRequest.assignment.order) match {
        case Left(Some(packagedProduct)) => sender() ! Pickup(packagedProduct)
        case Left(None) => sender() ! None
        case Right(discardOrder) =>
          orderProcessorOption.foreach(_ ! discardOrder)
          sender() ! discardOrder
      }
      context.become(readyForService(courierAssignments, storage.snapshot()))

    case assignment: CourierAssignment =>
      log.debug(s"Shelf manager received assignment: ${assignment.prettyString}")
      context.become(readyForService((
        courierAssignments + (assignment.order -> assignment)).take(MaximumCourierAssignmentCacheSize), storage))

    case RequestCapacityUtilization =>
      sender() ! storage.capacityUtilization

  }

  private def publishDiscardedOrders(discardedOrders: Iterable[DiscardOrder],
                                     courierAssignments: ListMap[Order, CourierAssignment]): ListMap[Order, CourierAssignment] = {
    var assignments = courierAssignments
    discardedOrders.foreach(discardedOrder => courierAssignments.get(discardedOrder.order) match {
      case Some(assignment: CourierAssignment) =>
        log.debug(s"Sending discarded order notice $discardedOrder to courier ${assignment.courierRef}")
        assignment.courierRef ! discardedOrder
        log.debug(s"Sending discarded order notice $discardedOrder to orderProcessor ${orderProcessorOption.get}")
        orderProcessorOption.foreach(_ ! discardedOrder)
        kitchenOption.foreach(_ ! discardedOrder)
        assignments -= assignment.order
      case _ =>
        orderProcessorOption.foreach(_ ! discardedOrder) // notify order processor to update order lifecycle
        kitchenOption.foreach(_ ! discardedOrder)
    })
    assignments
  }
}
