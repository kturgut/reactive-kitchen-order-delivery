package reactive.storage

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Timers}
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import com.typesafe.config.Config
import reactive.config.ShelfManagerConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.{CourierAssignment, Pickup, PickupRequest}
import reactive.delivery.Dispatcher.{DeclineCourierRequest, ReportAvailability}
import reactive.order.Order
import reactive.storage.ShelfManager.{ManageProductsOnShelves, RequestCapacityUtilization, StartAutomaticShelfLifeOptimization}
import reactive.{JacksonSerializable, ShelfManagerActor}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt

// @formatter:off
/**
 *   ShelfManager is a Stateless Actor. Parent Actor is Kitchen.
 *   Kitchen receives PackagedProducts from Kitchen and delivers them Courier that is assigned.
 *   Courier assigned for delivery sends a CourierAssignment to ShelfManager
 *   ShelfManager caches active CourierAssignments (FIFO cache with fixed size)
 *   When Couriers request Pickup if the PackagedProduct is still on the shelf it is returned, otherwise
 *   DiscardOrder is returned. ShelfManager has a cache that it tracks Courier Assignments.
 *
 * Reporting:
 *    ShelfManager reports its contents when overflow shelf capacity exceeds certain threshold 'OverflowUtilizationReportingThreshold'
 *    It also reports its before and after status of the shelves when products are discarded due to expiration or shelfCapacityExceeded.

 *
 * Timers: Recurring timer that triggers ManageProductsOnShelves every one second (from config) to automatically check
 *    the contents on the shelves and move them around or discard them as necessary.
 *    On this time, Dispatcher is also notified to update Kitchen with its status.
 *
 * ShelfManager handles the following incoming messages:
 *    PackagedProduct =>
 *       Puts the incoming package into the Overflow shelf, and starts optimization of overflow shelf.
 *       Any DiscardedOrders are published to Courier that is assigned for the package as well as to OrderProcessor
 *       for OrderLifeCycleManagement
 *    PickupRequest =>
 *       Send {Pickup | DiscardOrder | None} to Courier
 *       Check Storage for the product from Storage, if found return it, if not found return None.
 *       There is a small chance that the order may have expired just recently, if so a DiscardOrder notice might be returned
 *       to Courier instead.
 *       Validate that the courier who is requesting pickup is actually the one assigned for the delivery.
 *       Note that if product expiration is detected outside of this pickup request, the courier will get a direct
 *       message about the expiration and this PickupRequest will not be received.
 *    CourierAssignment =>
 *       Updates the registry for courier assignments.
 *    DeclineCourierRequest =>
 *       Remove product from shelf for declined order.
 *
 */
// @formatter:on

object ShelfManager {

  val ExpiredShelfLife = "ExpiredShelfLife"
  val ShelfCapacityExceeded = "ShelfCapacityExceeded"
  val CourierNotAvailable = "CourierNotAvailable"

  def props(kitchenRef: ActorRef, orderMonitorRef: ActorRef, dispatcher: ActorRef) =
    Props(new ShelfManager(kitchenRef, orderMonitorRef, dispatcher))

  case class DiscardOrder(order: Order, reason: String, createdOn: LocalDateTime) extends JacksonSerializable

  /**
   * Capacity Utilization is a number between [0,1]. 1 representing shelf is full at capacity
   */
  case class CapacityUtilization(overflow: Float, allShelves: Float, overflowAvailableSpace: Int)

  case object TimerKey

  case object StartAutomaticShelfLifeOptimization

  case object StopAutomaticShelfLifeOptimization

  case object ManageProductsOnShelves

  case object RequestCapacityUtilization

}

/**
 *  ShelfManager uses customized PriorityMailbox.
 *  This optimizes the processing of state changes while deprioritizing ManageProductsOnShelf which is relatively more expensive operation
 */
class ShelfManagerPriorityMailbox(settings: ActorSystem.Settings, config: Config) extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case _: SystemState => 0
    case StartAutomaticShelfLifeOptimization => 1
    case _: PackagedProduct => 2
    case _: CourierAssignment => 2
    case _: DeclineCourierRequest => 2
    case RequestCapacityUtilization => 3
    case _: PickupRequest => 4
    case ManageProductsOnShelves => 4
    case _ => 5
  }
)

class ShelfManager(kitchen: ActorRef, orderMonitor: ActorRef, dispatcher: ActorRef) extends Actor with ActorLogging with Timers {

  import ShelfManager._

  val config = ShelfManagerConfig(context.system)

  timers.startSingleTimer(TimerKey, StartAutomaticShelfLifeOptimization, 100 millis)

  override def receive: Receive =
    readyForService(ListMap.empty, Storage(log, config.shelfConfig(), config.CriticalTimeThresholdForSwappingInMillis.toMillis))

  def readyForService(registry: ListMap[Order, CourierAssignment], storage: Storage): Receive = {

    case _: SystemState | ReportStatus =>
      sender ! ComponentState(ShelfManagerActor, Operational, Some(self),
        1f - storage.overflow.capacityUtilization)

    case RequestCapacityUtilization =>
      sender() ! storage.capacityUtilization

    case StartAutomaticShelfLifeOptimization =>
      timers.startTimerWithFixedDelay(TimerKey, ManageProductsOnShelves, config.ShelfLifeOptimizationTimerDelay)

    case StopAutomaticShelfLifeOptimization =>
      log.warning("Stopping Automatic Shelf Life Optimization")
      timers.cancel(TimerKey)

    case ManageProductsOnShelves =>
      dispatcher.tell(ReportAvailability, kitchen)
      val updatedRegistry = publishDiscardedOrders(storage.snapshot(), storage, storage.optimizeShelfPlacement(), registry)
      log.debug(s"Overflow shelf capacity: [${storage.overflow.products.size / storage.overflow.capacity}]")
      if (storage.overflow.capacityUtilization > config.OverflowUtilizationReportingThreshold) {
        storage.reportStatus(true)
      }
      if (storage.overflow.capacityUtilization > config.OverflowUtilizationSafetyThreshold) {
        kitchen ! storage.capacityUtilization
      }
      context.become(readyForService(updatedRegistry, storage.snapshot()))

    case product: PackagedProduct =>
      log.debug(s"Putting new product ${product.prettyString} on shelf")
      val prevState = storage.snapshot()
      storage.fetchPackageForOrder(product.order).foreach(product =>
        log.warning(s"Discarding previously created product for same order with id: ${product.order.id}"))
      val updatedAssignments = publishDiscardedOrders(prevState, storage, storage.putPackageOnShelf(product), registry)
      if (storage.overflow.capacityUtilization >= config.OverflowUtilizationSafetyThreshold) {
        sender ! storage.capacityUtilization
      }
      self.tell(RequestCapacityUtilization, context.parent)
      dispatcher.tell(ReportAvailability, kitchen)
      context.become(readyForService(updatedAssignments, storage.snapshot()))

    /** If product found and not expired return it, else if expired return discard order instead and notify order processor
     */
    case pickupRequest: PickupRequest =>
      log.debug(s"Shelf manager received pickup request ${pickupRequest.assignment.prettyString}")
      registry.get(pickupRequest.assignment.order) match {
        case Some(assignedCourier) if assignedCourier != pickupRequest.assignment =>
          log.warning(s"Order with:id${pickupRequest.assignment.order.id} " +
            s"is assigned to be picked up by ${assignedCourier.courierName} not ${pickupRequest.assignment.courierName}")
        case _ =>
          log.debug(s"Courier assignment [${pickupRequest.assignment.prettyString}] not found in ShelfManager cache.")
      }
      storage.pickupPackageForOrder(pickupRequest.assignment.order) match {
        case Left(Some(packagedProduct)) =>
          sender() ! Pickup(packagedProduct)
        case Left(None) =>
          logRegistryContents(registry)
          sender() ! None
        case Right(discardOrder) =>
          orderMonitor ! discardOrder
          sender() ! discardOrder
      }
      context.become(readyForService(registry.filterNot(_._2 == pickupRequest.assignment), storage.snapshot()))

    case assignment: CourierAssignment =>
      log.debug(s"Shelf manager received assignment: ${assignment.prettyString}")
      context.become(readyForService((
        registry + (assignment.order -> assignment)).take(config.MaximumCourierAssignmentCacheSize), storage))

    case DeclineCourierRequest(product, _) =>
      val prevState = storage.snapshot()
      storage.fetchPackageForOrder(product.order)
      publishDiscardedOrders(prevState, storage, DiscardOrder(product.order, CourierNotAvailable, product.createdOn) :: Nil, registry)

    case other =>
      log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  private def publishDiscardedOrders(prevState: Storage, nextState: Storage, discardedOrders: Iterable[DiscardOrder],
                                     assignmentRegistry: ListMap[Order, CourierAssignment]): ListMap[Order, CourierAssignment] = {
    var assignments = assignmentRegistry
    logBeforeAndAfter(prevState, nextState, discardedOrders)
    discardedOrders.foreach(discardedOrder => assignmentRegistry.get(discardedOrder.order) match {
      case Some(assignment: CourierAssignment) =>
        log.debug(s"Sending notice for discarded order with id:${discardedOrder.order.id} to courier ${assignment.courierName}")
        assignment.courierRef ! discardedOrder
        discardedOrder.order.customer ! discardedOrder
        orderMonitor ! discardedOrder
        assignments -= assignment.order
      case _ =>
        discardedOrder.order.customer ! discardedOrder
        orderMonitor ! discardedOrder
    })
    self.tell(RequestCapacityUtilization, context.parent)
    assignments
  }

  private def logBeforeAndAfter(prevState: Storage, nextState: Storage, discardedOrders: Iterable[DiscardOrder]) = {
    if (discardedOrders.headOption.isDefined) {
      val buffer = new StringBuffer()
      val discardedInfo = discardedOrders.map(d => s"id:${d.order.id}>${d.reason}").mkString("::")
      prevState.reportToBuffer(buffer, s"\n\n\nContents BEFORE discarding products: [$discardedInfo]", true)
      nextState.reportToBuffer(buffer, s"\n\nContents AFTER discarding products: [$discardedInfo]\n", false)
      log.info(buffer.append("\n\n\n").toString)
    }
  }

  private def logRegistryContents(registry: ListMap[Order, CourierAssignment]) = {
    log.debug(s"Assignment registry contents: ${registry.map(oc => (s"id:${oc._1.id}", oc._2.courierName)).mkString(",")}")
  }

}
