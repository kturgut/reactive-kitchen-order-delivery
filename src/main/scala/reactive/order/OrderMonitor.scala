package reactive.order

import java.time.LocalDateTime

import akka.actor.{ActorLogging, Cancellable}
import akka.event.{LoggingAdapter, NoLogging}
import akka.persistence._
import reactive.config.OrderMonitorConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, Coordinator, SystemState}
import reactive.delivery.Courier.DeliveryComplete
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.{CourierNotAvailable, DiscardOrder, ExpiredShelfLife, ShelfCapacityExceeded}
import reactive.{JacksonSerializable, OrderMonitorActor}

import scala.collection.immutable.ListMap

// @formatter:off
/**
 * OrderMonitor is a Stateful Persistent Actor. Parent Actor is Coordinator.
 * It keeps its state in OrderLifeCycleState, and updates it as it receives 'commands' which are stored as 'events'.
 * After events are persisted successfully state is updated. OrderLifeCycleState maintains a fixed-size cache of all active orders'
 * lifecycles. Cache is implemented as a FIFO where completed orders are removed from cache first.
 *
 * As a stateful persistent actor OrderProcessor can be in one of these two states:
 *     1- receiveCommand: All commands are received here under normal operation.
 *     2- receiveRecover: During startup all recovery messages goes into this receiver.
 *
 * Timers:
 *     activityTimer: OrderProcessor has a scheduled timer to wakeup every 6 seconds to check if there has been any activity
 *     This timer sends ShutDown signal to Master in case of no life cycle events received during that time.
 *
 * OrderMonitor receives the following commands and creates the following events that are stored:
 *     Order => OrderRecord
 *     PackagedProduct => ProductRecord
 *     DiscardOrder => DiscardOrderRecord
 *     DeliveryComplete => DeliveryCompleteRecord
 *
 * If for any reason OrderMonitor has to restart after a crash, it replays these recorded events to recreate its state.
 * OrderMonitor takes regular snapshots of its state to disk to help improve the recovery time. These snapshots are taken after a fixed
 * number of events are recorded.
 *
 * Persistent OrderLifeCycle state can be queried. Not currently demonstrated.
 *
 * NOTE: To clean the persistent state:
 *    a) Send OrderMonitor ResetDatabase command
 *    b) Manually delete the files under these folders:
 *               target/reactive/journal
 *               target/reactive/snapshots
 */
// @formatter:on

case object OrderMonitor {

  sealed trait Event extends JacksonSerializable

  /**
   * Request cached state for a given order id. Since cache size is limited, You can also do persistent query if not in cache
   */
  case class RequestOrderLifeCycle(idOption: Option[Int] = None) extends JacksonSerializable

  case class OrderRecord(time: LocalDateTime, order: Order) extends Event

  // EVENTS

  case class ProductRecord(time: LocalDateTime, product: PackagedProduct) extends Event

  case class DeliveryCompleteRecord(time: LocalDateTime, delivery: DeliveryComplete) extends Event

  case class DiscardOrderRecord(time: LocalDateTime, discard: DiscardOrder) extends Event

  case class OrderLifeCycleState(orderCounter: Int = 0,
                                 lastOrderReceived: String = "Unknown",
                                 productionCounter: Int = 0,
                                 deliveryCounter: Int = 0,
                                 discardedOrderCounter: Int = 0,
                                 totalTipsReceived: Int = 0,
                                 eventCounter: Int = 0,
                                 activeOrders: ListMap[String, OrderLifeCycle] = ListMap.empty) extends JacksonSerializable {

    def update(event: Event, maxCacheSize: Int, log: LoggingAdapter = NoLogging): OrderLifeCycleState = {
      event match {
        case OrderRecord(_, order) =>
          copy(orderCounter = orderCounter + 1,
            lastOrderReceived = order.id,
            eventCounter = eventCounter + 1,
            activeOrders = updateCache(order,
              (lifeCycle: OrderLifeCycle) => lifeCycle, () => OrderLifeCycle(order), maxCacheSize))

        case ProductRecord(_, product) =>
          copy(productionCounter = productionCounter + 1,
            eventCounter = eventCounter + 1,
            activeOrders = updateCache(product.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(product, log), () => OrderLifeCycle(product.order, Some(product)), maxCacheSize))

        case DiscardOrderRecord(_, discard) =>
          copy(discardedOrderCounter = discardedOrderCounter + 1,
            eventCounter = eventCounter + 1,
            activeOrders = updateCache(discard.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(discard, log), () => OrderLifeCycle(discard.order, None, None, Some(discard)), maxCacheSize))

        case DeliveryCompleteRecord(_, delivery) =>
          copy(totalTipsReceived = totalTipsReceived + delivery.acceptance.tips,
            deliveryCounter = deliveryCounter + 1,
            eventCounter = eventCounter + 1,
            activeOrders = updateCache(delivery.product.order,
              (lifeCycle: OrderLifeCycle) => lifeCycle.update(delivery, log), () => OrderLifeCycle(delivery.assignment.order, None, Some(delivery)), maxCacheSize))
      }
    }

    /**
     * Create or update OrderLifeCycle entry in cache. Maintain cache size under max limit
     */
    private def updateCache(order: Order,
                            update: (OrderLifeCycle) => OrderLifeCycle,
                            create: () => OrderLifeCycle,
                            maxCacheSize: Int
                           ): ListMap[String, OrderLifeCycle] = {
      fifo(activeOrders.get(order.id) match {
        case Some(lifeCycle) =>
          activeOrders + (order.id -> update(lifeCycle))
        case None => activeOrders + (order.id -> create())
      }, maxCacheSize)
    }

    /**
     * First removes the completed orders and then others to maintain cache size
     */
    def fifo(in: ListMap[String, OrderLifeCycle], maxCacheSize: Int): ListMap[String, OrderLifeCycle] = {
      if (in.size > maxCacheSize) {
        val diff = in.size - maxCacheSize
        val toRemove = in.map(_._2).filter(_.completed).toList.reverse.take(diff).map(_.order.id)
        in.filter(kv => !toRemove.contains(kv._1)).take(maxCacheSize)
      }
      else in
    }

    def report(log: LoggingAdapter, message: String, verbose: Boolean = false) = {
      log.info(s"======== OrderLifeCycleMonitor $message: -cache size:${activeOrders.keys.size}  ===========")
      log.info(s"  Total orders received:$orderCounter.")
      log.info(s"  Total tips received:$totalTipsReceived.")
      log.info(s"  Total active orders in cache:${activeOrders.size}")
      log.info(s"  Active orders pending production: ${activeOrders.values.count(!_.produced)}.")
      log.info(s"  Active Orders pending delivery: ${activeOrders.values.count(!_.completed)}.")
      log.info(s"  Total orders delivered:$deliveryCounter.")
      log.info(s"  Total discard order notices:$discardedOrderCounter.")
      log.info(s"  Total discarded orders in cache:${activeOrders.values.filter(_.discarded).size}.")
      if (verbose) {
        log.info("========  Detail report for orders in cache below. For orders not in cache you can do persistent query!!! ==========")
        reportFiltered(activeOrders.values, (o: OrderLifeCycle) => (!o.completed), s"Incomplete (possibly lost in system)", log)
        reportFiltered(activeOrders.values, (o: OrderLifeCycle) =>
          (o.discarded && o.discard.get.reason == ExpiredShelfLife), s"Discarded for $ExpiredShelfLife", log)
        reportFiltered(activeOrders.values, (o: OrderLifeCycle) =>
          (o.discarded && o.discard.get.reason == ShelfCapacityExceeded), s"Discarded for $ShelfCapacityExceeded", log)
        reportFiltered(activeOrders.values, (o: OrderLifeCycle) =>
          (o.discarded && o.discard.get.reason == CourierNotAvailable), s"Discarded for $CourierNotAvailable", log)
        reportFiltered(activeOrders.values, (o: OrderLifeCycle) => (o.delivered), s"Delivered", log)
      }
    }

    private def reportFiltered(list: Iterable[OrderLifeCycle], filter: OrderLifeCycle => Boolean, prefix: String, log: LoggingAdapter) = {
      log.info(s"===     $prefix order summary from order-life-cycle cache:     ===")
      list.filter(filter).foreach { life => log info life.toShortString }
      log.info(s"")
    }

  }

  case object ResetDatabase

  case object OrderLifeCycleNotFoundInCache

}


class OrderMonitor extends PersistentActor with ActorLogging {

  import OrderMonitor._

  override val persistenceId: String = "OrderLifeCycleManagerPersistenceId"
  val config = OrderMonitorConfig(context.system)
  var state = OrderLifeCycleState()
  var schedule = createTimeoutWindow()

  /**
   * Normal Command handler
   */
  override def receiveCommand: Receive = {

    case _: SystemState | ReportStatus =>
      sender ! ComponentState(OrderMonitorActor, Operational, Some(self), 1)

    case order: Order =>
      val event = OrderRecord(LocalDateTime.now(), order)
      persist(event) { event =>
        log.info(s"Received ${state.orderCounter + 1}th order on ${event.time}:$order")
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
        log.debug(s"Order update: discarded: ${event.discard.order.name} " +
          s"for ${event.discard.reason} id:${event.discard.order.id}. Total discarded:${state.discardedOrderCounter + 1}")
        updateState(event)
      }

    case delivery: DeliveryComplete =>
      val event = DeliveryCompleteRecord(delivery.createdOn, delivery)
      persist(event) { event =>
        log.debug(s"Order update: delivered: ${event.delivery.prettyString()}")
        updateState(event)
      }

    case request: RequestOrderLifeCycle =>
      log.debug(s"Responding to order life cycle info request")
      val id = request.idOption.getOrElse(state.lastOrderReceived).toString
      sender() ! state.activeOrders.getOrElse(id, OrderLifeCycleNotFoundInCache)

    case Coordinator.Shutdown =>
      context.stop(self)

    case _: SaveSnapshotSuccess =>
      log.debug(s"Snapshot taken")

    case ResetDatabase =>
      log.warning("!!!Resetting OrderLifeCycle persistent store!!!!")
      this.deleteMessages(Long.MaxValue)
      this.deleteSnapshots(SnapshotSelectionCriteria.Latest)
  }

  /**
   * Update state from event.
   * Reset activity timer.
   * Checkpoint if needed
   */
  def updateState(event: Event): Unit = {
    mayBeCheckpoint()
    resetActivityTimer()
    state = state.update(event, config.MaxOrderLifeCycleCacheSize, log)
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
      state.report(log, "is shutting down", true)
      schedule.cancel()
      context.parent ! Coordinator.Shutdown
    }
  }

  def mayBeCheckpoint(): Unit = {
    if (state.eventCounter % config.MaxEventsWithoutCheckpoint == 0) {
      log.debug(s"Taking snapshot after ${state.eventCounter} events received")
      saveSnapshot(state)
    }
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
        state.report(log, "completed recovery of state upon restart")
      }

    case SnapshotOffer(metadata, snapshot: OrderLifeCycleState) =>
      log.info(s"Recovering from snapshot with timestamp: ${metadata.timestamp}")
      state = snapshot

    case error: SaveSnapshotFailure =>
      log.error(s"Error when taking a snapshot $error")
  }

}
