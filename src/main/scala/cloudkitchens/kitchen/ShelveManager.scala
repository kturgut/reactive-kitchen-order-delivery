package cloudkitchens.kitchen

import akka.actor.{Actor, ActorLogging, Timers}
import cloudkitchens.JacksonSerializable
import cloudkitchens.order.Order

import scala.concurrent.duration.DurationInt


object ShelfManager {

  case object TimerKey
  case object Start
  case object Stop
  case object ManageProductsOnShelves

  case class PutProductOnShelf(order:Order)

  sealed trait DiscardReason
  case object ExpiredShelfLife extends DiscardReason
  case object ShelfCapacityExceeded extends DiscardReason

  case class DiscardOrder(order:Order, reason:DiscardReason)  extends JacksonSerializable
}

class ShelfManager extends Actor with ActorLogging with Timers {
  import ShelfManager._
  timers.startSingleTimer(TimerKey, Start, 100 millis)

  override def receive:Receive = optimizeShelfLife(KitchenShelves())

  def optimizeShelfLife(kitchenShelves: KitchenShelves):Receive = {
    case Start =>
      log.info("Starting Shelf Manager that will reorder items on shelf periodically")
  //    timers.startPeriodicTimer(TimerKey,ManageProductsOnShelves, 10 second)
    case ManageProductsOnShelves =>
      log.info("Shuffling orders among shelves for longer shelf life, and discarding wasted orders")
    case PutProductOnShelf(order) =>
      log.info(s"Putting new product on shelf")
      kitchenShelves.createProductOnShelf(order)
    case Stop =>
        log.warning("Stopping Shelf Manager")

  }
}
