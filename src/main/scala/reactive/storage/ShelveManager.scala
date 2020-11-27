package reactive.storage

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import reactive.config.ShelfManagerConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.{CourierAssignment, Pickup, PickupRequest}
import reactive.delivery.Dispatcher.ReportAvailability
import reactive.order.{Order, Temperature}
import reactive.{JacksonSerializable, ShelfManagerActor}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt

/**
 * ShelfManager is a Stateless Actor. Parent Actor is Kitchen.
 * Kitchen receives PackagedProducts from Kitchen and delivers them Courier that is assigned.
 * Courier assigned for delivery sends a CourierAssignment to ShelfManager
 * ShelfManager caches active CourierAssignments (FIFO cache with fixed size)
 * When Couriers request Pickup if the PackagedProduct is still on the shelf it is returned, otherwise
 * DiscardOrder is returned. ShelfManager has a cache that it tracks Courier Assignments.
 *
 * ShelfManager currently does not have a mechanism put backpressure to Kitchen. It is in active state after creation.
 *
 * Timers: ShelfManager has an recurring timer that triggers ManageProductsOnShelves every one second to automatically check
 * the contents on the shelves and move them around or discard them as necessary
 *
 * ShelfManager handles the following incoming messages:
 * PackagedProduct => Puts the incoming package into the Overflow shelf, and starts optimization of overflow shelf.
 * Any DiscardedOrders are published to Courier that is assigned for the package as well as to OrderProcessor
 * for OrderLifeCycleManagement
 * PickupRequest => Send {Pickup | DiscardOrder | None} to Courier
 * Check Storage for the product from Storage, if found return it, if not found return None.
 * There is a small chance that the order may have expired just recently, if so a DiscardOrder notice might be returned
 * to Courier instead.
 * Note that if product expiration is detected outside of this pickup request, the courier will get a direct
 * message about the expiration and this PickupRequest will not be received.
 */
object ShelfManager {

  sealed trait DiscardReason
  case object ExpiredShelfLife extends DiscardReason
  case object ShelfCapacityExceeded extends DiscardReason

  def props(kitchenRef: ActorRef, orderMonitorRef: ActorRef, dispatcher:ActorRef) =
    Props(new ShelfManager(kitchenRef, orderMonitorRef,dispatcher))

  case class DiscardOrder(order: Order, reason: DiscardReason, createdOn: LocalDateTime) extends JacksonSerializable

  case object TimerKey

  case object StartAutomaticShelfLifeOptimization

  case object StopAutomaticShelfLifeOptimization

  case object ManageProductsOnShelves

  case object RequestCapacityUtilization

  /**
   *  Capacity Utilization is a number between [0,1]. 1 representing shelf is full at capacity
   */
  case class CapacityUtilization(overflow: Float, allShelves: Float)

}

class ShelfManager(kitchen: ActorRef, orderMonitor: ActorRef, dispatcher: ActorRef) extends Actor with ActorLogging with Timers {

  import ShelfManager._

  val config = ShelfManagerConfig(context.system)

  timers.startSingleTimer(TimerKey, StartAutomaticShelfLifeOptimization, 100 millis)

  override def receive: Receive = readyForService(ListMap.empty, Storage(log,config.shelfConfig(),config.CriticalTimeThresholdForSwappingInMillis.toMillis))

  def readyForService(courierAssignments: ListMap[Order, CourierAssignment], storage: Storage): Receive = {

    case _: SystemState  =>
      log.debug(s"Shelf Manager responding to SYSTEMSTATE with Copmonent state to $sender")
      sender ! ComponentState(ShelfManagerActor, Operational, Some(self), 1f - storage.shelves(Temperature.All).products.size / storage.shelves(Temperature.All).capacity)

    case ReportStatus =>
log.debug(s"Shelf Manager reporting state to $sender")
      sender ! ComponentState(ShelfManagerActor, Operational, Some(self), 1f - storage.shelves(Temperature.All).products.size / storage.shelves(Temperature.All).capacity)

    case StartAutomaticShelfLifeOptimization =>
      timers.startTimerWithFixedDelay(TimerKey, ManageProductsOnShelves,  config.ShelfLifeOptimizationTimerDelay)

    case StopAutomaticShelfLifeOptimization =>
      log.warning("Stopping Automatic Shelf Life Optimization")
      timers.cancel(TimerKey)

    case ManageProductsOnShelves =>
      dispatcher.tell(ReportAvailability, kitchen)
      val updatedAssignments = publishDiscardedOrders(storage.optimizeShelfPlacement(), courierAssignments)
      log.debug(s"OVERFLOW PRODUCT SIZE: ${storage.shelves(Temperature.All).products.size}")
      if (storage.capacityUtilization(Temperature.All) > config.OverflowUtilizationReportingThreshold)
        storage.reportStatus(true)
      if (storage.capacityUtilization(Temperature.All) > config.OverflowUtilizationSafetyThreshold)
        kitchen ! storage.capacityUtilization
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
          orderMonitor ! discardOrder
          sender() ! discardOrder
      }
      context.become(readyForService(courierAssignments, storage.snapshot()))

    case assignment: CourierAssignment =>
      log.debug(s"Shelf manager received assignment: ${assignment.prettyString}")
      context.become(readyForService((
        courierAssignments + (assignment.order -> assignment)).take(config.MaximumCourierAssignmentCacheSize), storage))

    case RequestCapacityUtilization =>
      sender() ! storage.capacityUtilization

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  private def publishDiscardedOrders(discardedOrders: Iterable[DiscardOrder],
                                     courierAssignments: ListMap[Order, CourierAssignment]): ListMap[Order, CourierAssignment] = {
    var assignments = courierAssignments
    discardedOrders.foreach(discardedOrder => courierAssignments.get(discardedOrder.order) match {
      case Some(assignment: CourierAssignment) =>
        log.debug(s"Sending discarded order notice $discardedOrder to courier ${assignment.courierRef}")
        assignment.courierRef ! discardedOrder
        log.debug(s"Sending discarded order notice $discardedOrder to orderMonitor ${orderMonitor}")
        orderMonitor ! discardedOrder
        kitchen ! discardedOrder
        assignments -= assignment.order
      case _ =>
        orderMonitor ! discardedOrder // notify order processor to update order lifecycle
        kitchen ! discardedOrder
    })
    assignments
  }
}
