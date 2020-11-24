package reactive.order

import java.time.LocalDateTime

import akka.actor.{ActorLogging, ActorRef, Cancellable}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import reactive.config.OrderMonitorConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, Coordinator, SystemState}
import reactive.delivery.Courier.DeliveryComplete
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder
import reactive.{JacksonSerializable, OrderMonitorActor}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.DurationInt

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


case object OrderMonitor {

  // EVENTS
  case class OrderRecord(time: LocalDateTime, order: Order) extends JacksonSerializable

  case class ProductRecord(time: LocalDateTime, product: PackagedProduct) extends JacksonSerializable

  case class DeliveryCompleteRecord(time: LocalDateTime, delivery: DeliveryComplete) extends JacksonSerializable

  case class DiscardOrderRecord(time: LocalDateTime, discard: DiscardOrder) extends JacksonSerializable

  case class OrderState(orderCounter:Int, lastOrderReceived:String, productionCounter:Int, deliveryCounter:Int,
                        discardedOrderCounter:Int, totalTipsReceived:Int,
                        activeOrders:ListMap[String, OrderLifeCycle] = ListMap.empty) {
  }

}


class OrderMonitor extends PersistentActor with ActorLogging {

  import OrderMonitor._
  val config = OrderMonitorConfig(context.system)

  override val persistenceId: String = "persistentOrderProcessorId"
  var orderCounter = 0
  var lastOrderReceived = "Unknown"
  var productionCounter = 0
  var deliveryCounter = 0
  var discardedOrderCounter = 0
  var totalTipsReceived = 0
  var activeOrders: ListMap[String, OrderLifeCycle] = ListMap.empty
  var schedule = createTimeoutWindow()

  // normal command handler
  override def receiveCommand(): Receive = {

    case _:SystemState | ReportStatus =>
      sender ! ComponentState(OrderMonitorActor,Operational, Some(self))

    case order: Order =>
      resetActivityTimer()
      val event = OrderRecord(LocalDateTime.now(), order)
      persist(event) { eventRecorded =>
        orderCounter += 1
        lastOrderReceived = eventRecorded.order.name
        log.info(s"Received ${orderCounter}th order on ${event.time}. Sending it kitchen:$order")
        updateState(order, (lifeCycle: OrderLifeCycle) => lifeCycle, () => OrderLifeCycle(order))
      }

    case product: PackagedProduct =>
      resetActivityTimer()
      val event = ProductRecord(LocalDateTime.now(), product)
      persist(event) { event =>
        productionCounter += 1
        log.debug(s"Order update: produced: ${event.product}")
        updateState(event.product.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(event.product, log),
          () => OrderLifeCycle(event.product.order, Some(event.product)))
      }
    case discard: DiscardOrder =>
      resetActivityTimer()
      val event = DiscardOrderRecord(LocalDateTime.now(), discard)
      persist(event) { event =>
        discardedOrderCounter += 1
        log.debug(s"Order update: discarded: ${event.discard.order.name} for ${event.discard.reason} id ${event.discard.order.id}. Total discarded:$discardedOrderCounter")
        updateState(event.discard.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(event.discard, log),
          () => OrderLifeCycle(event.discard.order, Some(event.discard.order)))
      }
    case delivery: DeliveryComplete =>
      resetActivityTimer()
      val event = DeliveryCompleteRecord(delivery.time, delivery)
      persist(event) { event =>
        val tip = event.delivery.acceptance.tips
        totalTipsReceived += tip
        deliveryCounter += 1
        log.debug(s"Order update: delivered: ${event.delivery.prettyString()}")
        updateState(event.delivery.assignment.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(event.delivery, log),
          () => OrderLifeCycle(event.delivery.assignment.order, Some(event.delivery.assignment)))
      }
    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  private def resetActivityTimer() = {
    schedule.cancel()
    schedule = createTimeoutWindow()
  }

  import reactive.system.dispatcher
  def createTimeoutWindow(): Cancellable = {
    context.system.scheduler.scheduleOnce(10 second) {
      log.info(s"No activity in the last 10 seconds. Shutting down!")
      reportState("is shutting down")
      schedule.cancel()
      context.parent ! Coordinator.Shutdown
    }
  }

  // This method is called on recovery e.g. in case system needs restart OrderHandler after a crash.
  // the restart would be handled by the parent actor as part of Supervision strategy.
  // This would give us opportunity to preserve state as, until the restart happens all incoming messages
  // would be queued in the mailbox of the OrderProcessor. Thus for example we can reissue an order to kitchen
  // if we had received the order but did not get the record that shows that kitchen has produced the Product yet!
  override def receiveRecover(): Receive = {

    case RecoveryCompleted =>
      if (orderCounter > 0) {
        reportState("completed recovery of state upon restart")
        // reissueOrdersNotProcessed()
      }

    case OrderRecord(date, order) =>
      orderCounter += 1
      lastOrderReceived = order.name
      log.info(s"Recovering  $order received on: $date  ")
      updateState(order, (lifeCycle: OrderLifeCycle) => lifeCycle, () => OrderLifeCycle(order))

    case ProductRecord(date, product) =>
      log.info(s"Recovering  $product received on: $date  ")
      productionCounter += 1
      updateState(product.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(product, log),
        () => OrderLifeCycle(product.order, Some(product)))

    case DiscardOrderRecord(date, discard) =>
      discardedOrderCounter += 1
      log.info(s"Recovering  $discard received on: $date  ")
      updateState(discard.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(discard, log),
        () => OrderLifeCycle(discard.order, Some(discard.order)))

    case DeliveryCompleteRecord(time, deliveryComplete) =>
      log.info(s"Recovering  $deliveryComplete")
      val tip = deliveryComplete.acceptance.tips
      totalTipsReceived += tip
      deliveryCounter += 1
      log.debug(s"Order update: delivered: ${deliveryComplete.prettyString()}")
      updateState(deliveryComplete.assignment.order, (lifeCycle: OrderLifeCycle) => lifeCycle.update(deliveryComplete, log),
        () => OrderLifeCycle(deliveryComplete.assignment.order, Some(deliveryComplete.assignment)))
  }

  /**
   * Create or update lifecycle entry for this order. Removed closed orders and keep cache size under max size
   */
  def updateState(order: Order,
                  update: (OrderLifeCycle) => OrderLifeCycle,
                  create: () => OrderLifeCycle
                 ): Unit =
    activeOrders = (activeOrders.get(order.id) match {
      case Some(lifeCycle) =>
        val updatedEntry = update(lifeCycle)
        if (updatedEntry.isComplete) activeOrders else activeOrders + (order.id -> updatedEntry)
      case None => activeOrders + (order.id -> create())
    }).take(config.MaximumOrderLifeCycleCacheSize)

  def reportState(state: String) = {
    log.info(s"OrderProcessor $state:")
    log.info(s"  Total orders received:$orderCounter.")
    log.info(s"  Total tips received:$orderCounter.")
    log.info(s"  Total active orders:${activeOrders.size}")
    log.info(s"  Active orders pending production: ${activeOrders.values.count(!_.produced)}.")
    log.info(s"  Active Orders pending delivery: ${activeOrders.values.count(_.isComplete)}.")
    log.info(s"  Total orders delivered:$deliveryCounter.")
    log.info(s"  Total orders discarded:$discardedOrderCounter.")
  }

  //  def reissueOrdersNotProcessed() = {
  //    activeOrders.values.filter(!_.produced).map(_.order).foreach(order => selectKitchenForOrder(kitchens, order) ! order)
  //    // TODO  request update for orders on delivery
  //  }
}
