package reactive.order

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import reactive.OrderProcessorActor
import reactive.config.OrderProcessorConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}


/**
 * OrderProcessor is a Stateful Persistent Actor. Parent Actor is CloudKitchens.
 *
 * What is ResourceManager State?
 *     - activeOrders: ListMap of OrderLifeCycle objects by orderId.
 *       This is acting as a fixed sized FIFO cache, maintaining only the active orders that are in the pipeline.
 *     - Various counters
 *         - Last order received, totalOrdersReceived, totalTipsReceived etc.
 *     - Kitchens registered. Though the current code is for one kitchen it is possible to support multiple and
 *       match orders to kitchens. TODO
 *       CLEANUP Note: Create a case class and put all state under OrderProcessorState. This will help implement Snapshot offer
 *       as we will simply save and retrieve the latest snapshot. Snapshots are created whenever we want.
 *       (ie. every 1K Orders for instance) TODO
 *
 * As a stateful persistent actor OrderProcessor can be in one of these two states:
 * 1- receiveCommand: All commands are received here under normal operation.
 * These commands are then persisted as Events on disk.
 * 2- receiveRecover: During startup all recovery messages goes into this receiver.
 *
 * Timers: OrderProcessor has a scheduled timer to wakeup every 6 seconds to check if there has been any activity
 * This timer sends ShutDown signal to Master in case of no life cycle events received during that time.
 *
 * OrderProcessor receives the following incoming messages:
 * KitchenReadyForService => Helps establish the connection between Kitchen and OrderProcessor
 * Order =>
 * Send back OrderDeceivedAck to Customer
 * Forward Order to select Kitchen
 * PackagedProduct (LifeCycleEvent) => Update state cache
 * DiscardOrder (LifeCycleEvent) => Update state cache
 * DeliveryComplete (LifeCycleEvent) => Update state cache
 *
 * Order LifeCycle
 * 1- Order "created"
 * 2- PackagedProduct is created (Kitchen prepared the order and sent to ShelfManager)
 * 3- CourierAssignment - currently not recorded TODO
 * 4- Pickup (of PackagedProduct from ShelfManager)  - currently not recorded TODO
 * 5- DeliveryAcceptanceRequest - sent from Courier to Customer - currently not recorded TODO
 * 6- DeliveryAcceptance - sent from Customer to Courier - currently not recorded TODO
 * 7- OrderComplete - Courier to OrderProcessor
 * 8- DiscardOrder - from ShelfManager
 * 9- Unknown termination - We should also record if for any reason delivery could not get completed for reasons outside of ShelfManager TODO
 *
 * During Recovery after a crash TODO
 * LifeCycle cache for active orders will be recreated by "Event Sourcing". This gives us opportunity for disaster recovery:
 * Based on the above Order LifeCycle state transition, we can check if the order is recent, and we can follow up with Kitchen or Courier
 * and reissue as needed.
 */


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
      sender ! ComponentState(OrderProcessorActor,ComponentState.Initializing, Some(self))

    case state:SystemState =>
      (state.orderMonitorOption, state.kitchenOption, state.dispatcherOption) match {
        case (Some(orderMonitor), Some(kitchen),Some(dispatcher)) =>
          val name = kitchen.path.toStringWithoutAddress
          log.info(s"A new kitchen:${name} and dispatcher:${dispatcher.path.toStringWithoutAddress} registered to receive orders.")
          sender() ! ComponentState(OrderProcessorActor,Operational, Some(self), 1.0f)
          unstashAll()
          context.become(openForService(orderMonitor, kitchens + (name -> kitchen), dispatcher))
        case _ => log.warning(s"OrderProcessor can not be initialized without kitchen,orderMonitor and dispatcher")
      }

    case _: Order => stash()
  }

  def openForService(orderMonitor: ActorRef, kitchens: Map[String, ActorRef], dispatcher: ActorRef): Receive = {

    case ReportStatus =>
      sender ! ComponentState(OrderProcessorActor,Operational, Some(self))

    case order: Order =>
      sender() ! OrderReceived(order.id)
      orderMonitor ! order
      orderCounter += 1
      selectKitchenForOrder(kitchens, order) ! order

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }


  // This where we would normally match orders to kitchens if multiple kitchens were registered
  def selectKitchenForOrder(kitchens: Map[String, ActorRef], order: Order): ActorRef = {
    assert(kitchens.nonEmpty)
    kitchens.values.head
  }
}
