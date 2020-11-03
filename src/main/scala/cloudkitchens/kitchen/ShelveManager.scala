package cloudkitchens.kitchen


import java.time.LocalDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import cloudkitchens.JacksonSerializable
import cloudkitchens.delivery.Courier.{CourierAssignment, Pickup, PickupRequest}
import cloudkitchens.order.Order
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt


object ShelfManager {

  case object TimerKey
  case object StartAutomaticShelfLifeOptimization
  case object StopAutomaticShelfLifeOptimization
  case object ManageProductsOnShelves

  val MaximumCourierAssignmentCacheSize = 100


  sealed trait DiscardReason


  // TODO Turn discard reason to trait
  case class DiscardOrder(order:Order, reason:String, createdOn:LocalDateTime = LocalDateTime.now())  extends JacksonSerializable
  val ExpiredShelfLife = "ExpiredShelfLife"
  val ShelfCapacityExceeded = "ShelfCapacityExceeded"

  def props(orderProcessorOption:Option[ActorRef]) = Props(new ShelfManager(orderProcessorOption))
}

class ShelfManager(orderProcessorOption:Option[ActorRef]=None)
                                                    extends Actor with ActorLogging with Timers {
  import ShelfManager._
  timers.startSingleTimer(TimerKey, StartAutomaticShelfLifeOptimization, 100 millis)

  override def receive:Receive = readyForService(ListMap.empty, KitchenShelves(log))

  def readyForService(courierAssignments:ListMap[Order,CourierAssignment], kitchenShelves: KitchenShelves):Receive = {

    case StartAutomaticShelfLifeOptimization =>
      timers.startTimerWithFixedDelay(TimerKey,ManageProductsOnShelves, 1 second)

    case StopAutomaticShelfLifeOptimization =>
      timers.cancel(TimerKey)

    case ManageProductsOnShelves =>
      val updatedAssignments = broadcastDiscardedOrders(kitchenShelves.optimizeShelfPlacement(),courierAssignments)
      context.become(readyForService(updatedAssignments,kitchenShelves.copy()))

    case product:PackagedProduct =>
      log.info(s"Putting new product on shelf")
      val updatedAssignments = broadcastDiscardedOrders(kitchenShelves.putPackageOnShelf(product),courierAssignments)
      context.become(readyForService(updatedAssignments,kitchenShelves.copy()))

    case pickupRequest:PickupRequest =>
      log.debug(s"Shelf manager received pickup request ${pickupRequest.assignment}")
      sender () ! (kitchenShelves.removePackageForOrder(pickupRequest.assignment.order) match {
        case Some(product:PackagedProduct) =>
          val pickup = Pickup(product)
          orderProcessorOption.foreach(_ ! pickup)
          sender() ! pickup
          context.become(readyForService(courierAssignments,kitchenShelves.copy()))
        case None => None
      })

    case assignment:CourierAssignment =>
      log.debug(s"Shelf manager received courier assignment: $assignment")
      context.become(readyForService((
        courierAssignments + (assignment.order -> assignment)).take(MaximumCourierAssignmentCacheSize), kitchenShelves))

    case StopAutomaticShelfLifeOptimization =>
        log.warning("Stopping Shelf Manager")
  }
  private def broadcastDiscardedOrders(discardedOrders: Iterable[DiscardOrder],
                                       courierAssignments:ListMap[Order,CourierAssignment]):ListMap[Order,CourierAssignment] = {
    var assignments = courierAssignments
    discardedOrders.foreach(discardedOrder => courierAssignments.get(discardedOrder.order) match {
      case Some(assignment:CourierAssignment) =>
        log.debug(s"Sending discarded order notice $discardedOrder to courier ${assignment.courierRef}")
        assignment.courierRef ! discardedOrder
        log.debug(s"Sending discarded order notice $discardedOrder to orderProcessor ${orderProcessorOption.get}")
        orderProcessorOption.foreach(_ ! discardedOrder)

        assignments -= assignment.order
      case _ => // update order lifecycle
        orderProcessorOption.foreach(_ ! discardedOrder)
    })
    assignments
  }


}
