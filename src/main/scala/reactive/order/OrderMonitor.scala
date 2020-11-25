package reactive.order

import java.time.LocalDateTime

import akka.actor.{ActorLogging, ActorRef, Cancellable}
import akka.event.LoggingAdapter
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess, PersistentActor, RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer, SnapshotSelectionCriteria}
import reactive.config.OrderMonitorConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, Coordinator, SystemState}
import reactive.delivery.Courier.DeliveryComplete
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder
import reactive.{JacksonSerializable, OrderMonitorActor}
import sun.tools.jconsole.ProxyClient.SnapshotMBeanServerConnection

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

  case object ResetDatabase

  /**
   * Request cached state for a given order id. Since cache size is limited, You can also do persistent query if not in cache
   */
  case class RequestOrderLifeCycle(idOption:Option[Int]=None) extends JacksonSerializable
  case object OrderLifeCycleNotFoundInCache

  // EVENTS

  sealed trait Event extends JacksonSerializable

  case class OrderRecord(time: LocalDateTime, order: Order) extends Event

  case class ProductRecord(time: LocalDateTime, product: PackagedProduct) extends Event

  case class DeliveryCompleteRecord(time: LocalDateTime, delivery: DeliveryComplete) extends Event

  case class DiscardOrderRecord(time: LocalDateTime, discard: DiscardOrder) extends Event

  case class OrderLifeCycleState(orderCounter:Int=0,
                                 lastOrderReceived:String="Unknown",
                                 productionCounter:Int=0,
                                 deliveryCounter:Int=0,
                                 discardedOrderCounter:Int=0,
                                 totalTipsReceived:Int=0,
                                 eventCounter:Int=0,
                                 activeOrders:ListMap[String, OrderLifeCycle] = ListMap.empty) extends JacksonSerializable {

    def update(event:Event, maxCacheSize:Int, log:LoggingAdapter):OrderLifeCycleState = {
      event match {
        case OrderRecord(_,order) =>
          copy(orderCounter=orderCounter+1,
            lastOrderReceived=order.id,
            eventCounter=eventCounter+1,
            activeOrders=updateCache(order,
              (lifeCycle: OrderLifeCycle) => lifeCycle, () => OrderLifeCycle(order),maxCacheSize))

        case ProductRecord(_,product) =>
          copy(productionCounter=productionCounter+1,
            eventCounter=eventCounter+1,
            activeOrders=updateCache(product.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(product, log),() => OrderLifeCycle(product.order, Some(product)),maxCacheSize))

        case DiscardOrderRecord(_,discard) =>
          copy(discardedOrderCounter=discardedOrderCounter + 1,
            eventCounter=eventCounter+1,
            activeOrders=updateCache(discard.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(discard, log),() =>OrderLifeCycle(discard.order, None, None,Some(discard)),maxCacheSize))

        case DeliveryCompleteRecord(_,delivery) =>
          copy(totalTipsReceived=totalTipsReceived + delivery.acceptance.tips,
            deliveryCounter = deliveryCounter  + 1,
            eventCounter=eventCounter + 1,
            activeOrders=updateCache(delivery.product.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(delivery, log), () => OrderLifeCycle(delivery.assignment.order, None, Some(delivery)),maxCacheSize))
      }
    }

    def updateCache(order: Order,
                    update: (OrderLifeCycle) => OrderLifeCycle,
                    create: () => OrderLifeCycle,
                    maxCacheSize:Int
                   ): ListMap[String, OrderLifeCycle] = {
      (activeOrders.get(order.id) match {
        case Some(lifeCycle) =>
          val updatedEntry = update(lifeCycle)
          if (updatedEntry.isComplete) activeOrders else activeOrders + (order.id -> updatedEntry)
        case None => activeOrders + (order.id -> create())
      }).take(maxCacheSize)
    }

    def report(log:LoggingAdapter, message: String) = {
      log.info(s"OrderLifeCycleMonitor $message:")
      log.info(s"  Total orders received:$orderCounter.")
      log.info(s"  Total tips received:$totalTipsReceived.")
      log.info(s"  Total active orders:${activeOrders.size}")
      log.info(s"  Active orders pending production: ${activeOrders.values.count(!_.produced)}.")
      log.info(s"  Active Orders pending delivery: ${activeOrders.values.count(_.isComplete)}.")
      log.info(s"  Total orders delivered:$deliveryCounter.")
      log.info(s"  Total orders discarded:$discardedOrderCounter.")
    }

  }

}


class OrderMonitor extends PersistentActor with ActorLogging {

  import OrderMonitor._
  val config = OrderMonitorConfig(context.system)
  var state = OrderLifeCycleState()
  var schedule = createTimeoutWindow()

  override val persistenceId: String = "OrderLifeCycleManagerPersistenceId"

  /**
   * Normal Command handler
   */
  override def receiveCommand: Receive = {

    case _:SystemState | ReportStatus =>
      sender ! ComponentState(OrderMonitorActor,Operational, Some(self))

    case order: Order =>
      val event = OrderRecord(LocalDateTime.now(), order)
      persist(event) { event =>
        log.info(s"Received ${state.orderCounter}th order on ${event.time}:$order")
        updateState(event)
      }

    case product: PackagedProduct =>
      val event = ProductRecord(LocalDateTime.now(), product)
      persist(event) { event =>
        log.debug(s"Order update: produced: ${event.product}")
        updateState(event)
      }

    case discard: DiscardOrder =>
      val event = DiscardOrderRecord(LocalDateTime.now(), discard)
      persist(event) { event =>
        log.debug(s"Order update: discarded: ${event.discard.order.name} for ${event.discard.reason} id ${event.discard.order.id}. Total discarded:${state.discardedOrderCounter}")
        updateState(event)
      }

    case delivery: DeliveryComplete =>
      val event = DeliveryCompleteRecord(delivery.time, delivery)
      persist(event) { event =>
        log.debug(s"Order update: delivered: ${event.delivery.prettyString()}")
        updateState(event)
      }

    case request:RequestOrderLifeCycle =>
      log.debug(s"Responding to order life cycle info request")
      val id = request.idOption.getOrElse(state.lastOrderReceived).toString
      sender() ! state.activeOrders.getOrElse(id, OrderLifeCycleNotFoundInCache)

    case Coordinator.Shutdown =>
      context.stop(self)

    case ResetDatabase =>
      log.warning("!!!Resetting OrderLifeCycle persistent store!!!!")
      this.deleteMessages(Long.MaxValue)
      this.deleteSnapshots(SnapshotSelectionCriteria.Latest)

  }

  /**
   * This method is called on recovery e.g. in case system needs restart OrderHandler after a crash.
   * Restart would be handled by the Coordinator parent actor as part of its Supervision strategy.
   * Coordinator may choose to replay orders received right before crash after recovery. (Not implemented!)
   */
  override def receiveRecover: Receive = {

    case event@OrderRecord(date, order) =>
      log.debug(s"Recovering  $order received on: $date")
      updateState(event)

    case event@ProductRecord(date, product) =>
      log.debug(s"Recovering  $product received on: $date")
      updateState(event)

    case event@DiscardOrderRecord(date, discard) =>
      log.debug(s"Recovering  $discard received on: $date")
      updateState(event)

    case event@DeliveryCompleteRecord(date, deliveryComplete) =>
      log.debug(s"Recovering  ${deliveryComplete.prettyString()} received on $date")
      updateState(event)

    case RecoveryCompleted =>
      if (state.orderCounter > 0) {
        state.report(log,"completed recovery of state upon restart")
      }

    case SnapshotOffer(metadata,snapshot:OrderLifeCycleState) =>
      log.info(s"Recovering from snapshot with timestamp: ${metadata.timestamp}")
      state = snapshot

    case error:SaveSnapshotFailure =>
      log.error(s"Error when taking a snapshot $error")
  }

  import reactive.system.dispatcher
  private def resetActivityTimer() = {
    schedule.cancel()
    schedule = createTimeoutWindow()
  }

  /**
   * Will shutdown system if no Order activity
   */
  def createTimeoutWindow(): Cancellable = {
    context.system.scheduler.scheduleOnce(config.InactivityShutdownTimer) {
      log.info(s"No activity in the last ${config.InactivityShutdownTimer.toSeconds} seconds!")
      state.report(log,"is shutting down")
      schedule.cancel()
      context.parent ! Coordinator.Shutdown
    }
  }

  /**
   * Update state from event.
   * Reset activity timer.
   * Checkpoint if needed
   */
  def updateState(event:Event):Unit = {
    mayBeCheckpoint()
    resetActivityTimer()
    state = state.update(event,config.MaxOrderLifeCycleCacheSize,log)
  }

  def mayBeCheckpoint():Unit = {
    if (state.eventCounter % config.MaxEventsWithoutCheckpoint == 0) {
      log.debug(s"Taking snapshot after ${state.eventCounter} events received")
      saveSnapshot(state)
    }
  }

}
