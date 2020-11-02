package cloudkitchens.kitchen


import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import cloudkitchens.JacksonSerializable
import cloudkitchens.delivery.Courier.{Pickup, PickupRequest}

import cloudkitchens.order.Order

import scala.concurrent.duration.DurationInt


object ShelfManager {

  case object TimerKey
  case object Start
  case object Stop
  case object ManageProductsOnShelves


  sealed trait DiscardReason
  case object ExpiredShelfLife extends DiscardReason
  case object ShelfCapacityExceeded extends DiscardReason

  case class DiscardOrder(order:Order, reason:DiscardReason)  extends JacksonSerializable
  case class PickupReceipt(order:Order, reason:DiscardReason)  extends JacksonSerializable

  def props(courierDispatcher:ActorRef, orderProcessorOption:Option[ActorRef]) = Props(new ShelfManager(courierDispatcher,orderProcessorOption))
}

class ShelfManager(courierDispatcher:ActorRef, orderProcessorOption:Option[ActorRef]=None)
                                                    extends Actor with ActorLogging with Timers {
  import ShelfManager._
  timers.startSingleTimer(TimerKey, Start, 100 millis)

  override def receive:Receive = readyForService(KitchenShelves(log))

  def readyForService(kitchenShelves: KitchenShelves):Receive = {
    case Start =>
      log.info("Starting Shelf Manager that will reorder items on shelf periodically")
      timers.startTimerWithFixedDelay(TimerKey,ManageProductsOnShelves, 10 second)
    case ManageProductsOnShelves =>
      log.debug("Shuffling orders among shelves for longer shelf life, and discarding wasted orders")
    case product:PackagedProduct =>
      log.info(s"Putting new product on shelf")
      kitchenShelves.putOnShelf(product)
      context.become(readyForService(kitchenShelves.copy()))

    case pickupRequest:PickupRequest =>
      log.debug(s"Shelf manager received pickup request ${pickupRequest.assignment}")
      sender () ! (kitchenShelves.getPackageForOrder(pickupRequest.assignment.order) match {
        case Some(product:PackagedProduct) =>
          val pickup = Pickup(product)
          orderProcessorOption.foreach(_ ! pickup)
          sender() ! pickup
          context.become(readyForService(kitchenShelves.copy()))
        case None => None
      })
    case Stop =>
        log.warning("Stopping Shelf Manager")

  }

}
