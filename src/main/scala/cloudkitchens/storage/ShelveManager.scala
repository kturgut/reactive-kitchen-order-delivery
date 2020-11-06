package cloudkitchens.storage

import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import cloudkitchens.JacksonSerializable
import cloudkitchens.delivery.Courier.{CourierAssignment, Pickup, PickupRequest}
import cloudkitchens.order.Order

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt


object ShelfManager {

  case object TimerKey

  case object StartAutomaticShelfLifeOptimization

  case object StopAutomaticShelfLifeOptimization

  case object ManageProductsOnShelves

  val MaximumCourierAssignmentCacheSize = 100

  // TODO Turn discard reason to trait
  case class DiscardOrder(order: Order, reason: String, createdOn: LocalDateTime) extends JacksonSerializable

  val ExpiredShelfLife = "ExpiredShelfLife"
  val ShelfCapacityExceeded = "ShelfCapacityExceeded"

  def props(orderProcessorOption: Option[ActorRef]) = Props(new ShelfManager(orderProcessorOption))
}

class ShelfManager(orderProcessorOption: Option[ActorRef] = None) extends Actor with ActorLogging with Timers {

  import ShelfManager._

  timers.startSingleTimer(TimerKey, StartAutomaticShelfLifeOptimization, 100 millis)

  override def receive: Receive = readyForService(ListMap.empty, Storage(log))

  def readyForService(courierAssignments: ListMap[Order, CourierAssignment], storage: Storage): Receive = {

    case StartAutomaticShelfLifeOptimization =>
      timers.startTimerWithFixedDelay(TimerKey, ManageProductsOnShelves, 1 second)

    case StopAutomaticShelfLifeOptimization =>
      log.warning("Stopping Automatic Shellf Life Optimization")
      timers.cancel(TimerKey)

    case ManageProductsOnShelves =>
      val updatedAssignments = publishDiscardedOrders(storage.optimizeShelfPlacement(), courierAssignments)
      if (!storage.hasAvailableSpace)
        storage.reportStatus(true)
      context.become(readyForService(updatedAssignments, storage.copy()))

    case product: PackagedProduct =>
      log.debug(s"Putting new product ${product.prettyString} on shelf")
      val updatedAssignments = publishDiscardedOrders(storage.putPackageOnShelf(product), courierAssignments)
      context.become(readyForService(updatedAssignments, storage.copy()))

    // If product found and not expired return it, else if expired return discard order instead and notify order processor
    case pickupRequest: PickupRequest =>
      log.debug(s"Shelf manager received pickup request ${pickupRequest.assignment.prettyString}")
      storage.pickupPackageForOrder(pickupRequest.assignment.order) match {
        case Left(Some(packagedProduct)) => sender() ! Pickup(packagedProduct)
        case Left(None) => sender() ! None
        case Right(discardOrder) =>
          orderProcessorOption.foreach(_ ! discardOrder)
          sender() ! discardOrder
      }
      context.become(readyForService(courierAssignments, storage.copy()))

    case assignment: CourierAssignment =>
      log.debug(s"Shelf manager received assignment: ${assignment.prettyString}")
      context.become(readyForService((
        courierAssignments + (assignment.order -> assignment)).take(MaximumCourierAssignmentCacheSize), storage))

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

        assignments -= assignment.order
      case _ => orderProcessorOption.foreach(_ ! discardedOrder) // notify order processor to update order lifecycle
    })
    assignments
  }
}
