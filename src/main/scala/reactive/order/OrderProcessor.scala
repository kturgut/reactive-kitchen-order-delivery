package reactive.order

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import reactive.OrderProcessorActor
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}

// @formatter:off
/**
 * OrderProcessor is a Stateless Actor. Parent Actor is Coordinator.
 * OrderProcessor forwards the orders it receives to Kitchen after validation.
 * Currently no throttle or backpressure is implemented between OrderProcessor and Kitchen.
 *
 * OrderProcessor can be in one of two states at any one time:
 *   1- closedForService
 *   2- openForService
 *
 * OrderProcessor handles the following incoming messages
 *   when closedForService:
 *        SystemState (with Dispatcher, Kitchen and OrderMonitor actors) => Initializes the OrderProcessor

 *   when openForService:
 *       Order => forward to Kitchen
 */
// @formatter:on

case object OrderProcessor {

  val MaximumSizeForLifeCycleCache = 200

  case class OrderReceived(id: String)

}


class OrderProcessor extends Actor with ActorLogging with Stash {

  import OrderProcessor._

  var orderCounter = 0

  override def receive: Receive = closedForService(Map.empty)

  def closedForService(kitchens: Map[String, ActorRef]): Receive = {

    case ReportStatus =>
      sender ! ComponentState(OrderProcessorActor, ComponentState.Initializing, Some(self))

    case state: SystemState =>
      (state.orderMonitorOption, state.kitchenOption, state.dispatcherOption) match {
        case (Some(orderMonitor), Some(kitchen), Some(dispatcher)) =>
          val name = kitchen.path.toStringWithoutAddress
          log.info(s"A new kitchen:${name} and dispatcher:${dispatcher.path.toStringWithoutAddress} registered to receive orders.")
          sender() ! ComponentState(OrderProcessorActor, Operational, Some(self), 1.0f)
          unstashAll()
          context.become(openForService(orderMonitor, kitchens + (name -> kitchen), dispatcher))
        case _ => log.warning(s"OrderProcessor can not be initialized without kitchen,orderMonitor and dispatcher")
      }

    case _: Order => stash()
  }

  def openForService(orderMonitor: ActorRef, kitchens: Map[String, ActorRef], dispatcher: ActorRef): Receive = {

    case ReportStatus =>
      sender ! ComponentState(OrderProcessorActor, Operational, Some(self))

    case order: Order =>
      OrderValidator.validate(order, log) match {
        case Some(validOrder) =>
          sender() ! OrderReceived(validOrder.id)
          orderMonitor ! order
          orderCounter += 1
          selectKitchenForOrder(kitchens, order) ! order
        case None => log.warning(s"Skipping processing invalid order with id:${order.id}. See logs for detailed error messages. ${order}")
          orderCounter += 1
      }

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }


  // This where we would normally match orders to kitchens if multiple kitchens were registered
  def selectKitchenForOrder(kitchens: Map[String, ActorRef], order: Order): ActorRef = {
    assert(kitchens.nonEmpty)
    kitchens.values.head
  }
}
