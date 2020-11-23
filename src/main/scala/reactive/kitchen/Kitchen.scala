package reactive.kitchen

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Stash, Terminated, Timers}
import akka.util.Timeout
import reactive.coordinator.ComponentState.{Operational, UnhealthyButOperational}
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Dispatcher
import reactive.delivery.Dispatcher.{CourierAvailability, NumberOfCouriersToRecruitInBatches, RecruitCouriers}
import reactive.order.Order
import reactive.storage.ShelfManager.{CapacityUtilization, DiscardOrder}
import reactive.storage.{PackagedProduct, ShelfManager}
import reactive.{JacksonSerializable, KitchenActor, ShelfManagerActor}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Kitchen is a Stateless Actor. Parent Actor is CloudKitchens.
 * Kitchen receives Orders from OrderProcessor and prepares PackagedProduct.
 * PackagedProducts are sent to ShelfManager for storage.
 *
 * CourierDispatcher can be in one of two states at any one time
 * 1- closedForService
 * 2- openForService
 *
 * Kitchen handles the following incoming messages
 * when closedForService:
 * InitializeKitchen => This helps connect Kitchen with OrderProcessor and CourierDispatcher
 * It creates its child actor ShelfManager and sends KitchenReadyForService message to it to connect it
 * with these other actors
 * when openForService:
 * Order => Prepare the order and then create a PackagedProduct
 * Creation of PackagedProduct is tracked as an OrderLifeCycle event, so send it to OrderProcessor.
 * Also send the PackagedProduct to ShelfManager for storage.
 * Also notify CourierDispatcher that a new PackagedProduct is created and it should allocate a Courier for delivery
 *
 * TODO:
 *  - Throughput: Currently throttling is done at Customer -> OrderProcessor. We can implement a custom BackPressure
 *    Logic between OrderProcessor and Kitchen. One way of doing this is observing the capacity utilization in Storage
 *    by interacting with Shelf Manager and stashing messages until Capacity utilization falls to a certain threshold
 *
 *  - CircuitBreaker: If for any reason either the CourierDispatcher or ShelfManager becomes unresponsive or overloaded,
 *    we can put a CircuitBreaker in between and stash the incoming messages until they get stabilized.
 */

object Kitchen {
  val TurkishCousine = "Turkish"
  val AmericanCousine = "American"
  val DelayInMillisIfStorageIsFull = 1000
  val DelayInMillisIfProductIsDiscarded = 1500
  val DelayInMillisIfOverflowIsAboveThreshold = 1000
  val OverflowUtilizationSafetyThreshold = 0.9
  val DelayInMillisIfDispatcherIsNotAvailable = 3000
  val DelayInMillisIfDispatcherAvailabilityBelowThreshold = 2000

  def props(name: String, expectedOrdersPerSecond: Int) = Props(new Kitchen(name, expectedOrdersPerSecond))

  case class InitializeKitchen(orderMonitor: ActorRef,
                               dispatcher: ActorRef) extends JacksonSerializable

  case object AttemptToResumeTakingOrders


  case class ReadyForService(kitchenName: String, expectedOrdersPerSecond: Int, kitchenRef: ActorRef,
                             shelfManagerRef: ActorRef) extends JacksonSerializable

}


class Kitchen(name: String, expectedOrdersPerSecond: Int) extends Actor with ActorLogging with Timers with Stash {

  import Kitchen._
  import akka.pattern.ask
  import reactive.system.dispatcher

  implicit val timeout = Timeout(300 milliseconds)

  override val receive: Receive = closedForService

  def closedForService: Receive = {
    case state: SystemState =>
      (state.dispatcherOption, state.orderMonitorOption) match {
        case (Some(dispatcher), Some(orderMonitor)) =>
          log.debug(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with ${dispatcher} and ${orderMonitor}")
          val shelfManager = context.actorOf(ShelfManager.props(self, orderMonitor), ShelfManagerActor)
          context.watch(shelfManager)
          dispatcher.tell(state.update(ComponentState(ShelfManagerActor, Operational, Some(shelfManager))), sender())
          dispatcher ! RecruitCouriers(NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
          becomeActive(shelfManager, orderMonitor, dispatcher)
      }

    case ReportStatus => sender() ! ComponentState(KitchenActor, ComponentState.Initializing, Some(self))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  def active(shelfManager: ActorRef, orderMonitor: ActorRef, courierDispatcher: ActorRef): Receive = {
    case order: Order =>
      val product = PackagedProduct(order)
      log.info(s"Kitchen $name prepared order for '${order.name}' id:${order.id}. Sending it to ShelfManager for courier pickup")
      shelfManager ! PackagedProduct(order)
      courierDispatcher ! product
      orderMonitor ! product

    case dispatcher: CourierAvailability if dispatcher.health < Dispatcher.MinHealthThreshold =>
      val delay = if (dispatcher.available == 0) DelayInMillisIfDispatcherIsNotAvailable else DelayInMillisIfDispatcherAvailabilityBelowThreshold
      suspend(delay, shelfManager, orderMonitor, courierDispatcher)

    case _: DiscardOrder => suspend(DelayInMillisIfProductIsDiscarded, shelfManager, orderMonitor, courierDispatcher)

    case shelfCapacity: CapacityUtilization => checkShelfCapacity(shelfCapacity, shelfManager, orderMonitor, courierDispatcher)

    case ReportStatus => sender() ! ComponentState(KitchenActor, Operational, Some(self))

    case Terminated(ref) =>
      log.info(s"Shelf Manager ${ref.path} is terminated")
      context.become(closedForService)

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }


  /**
   * If overflow capacity utilization exceeds OverflowUtilizationSafetyThreshold is received,
   * Kitchen temporarily suspends processing incoming orders for DelayInMillisIfOverflowIsAboveThreshold milliseconds.
   *
   * If DiscardOrder is received, Kitchen temporarily suspends processing incoming orders for
   * DelayInMillisIfProductIsDiscarded milliseconds.
   *
   * Before Kitchen resumes processing it confirms that Shelf overflow capacity utilization is below threshold.
   */
  def suspendIncomingOrders(schedule: Cancellable, shelfManager: ActorRef, orderMonitor: ActorRef, courierDispatcher: ActorRef): Receive = {
    case order: Order =>
      log.debug(s"Kitchen stashing message received in suspended state: $order")
      stash()

    case _: DiscardOrder =>
      schedule.cancel()
      context.become(suspendIncomingOrders(resume(self, DelayInMillisIfProductIsDiscarded), shelfManager, orderMonitor, courierDispatcher))

    case AttemptToResumeTakingOrders =>
      schedule.cancel()
      (shelfManager ? ShelfManager.RequestCapacityUtilization).onComplete {
        case Success(shelfCapacity: CapacityUtilization) => checkShelfCapacity(shelfCapacity, shelfManager, orderMonitor, courierDispatcher, Some(schedule))
        case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
      }

    case shelfCapacity: CapacityUtilization => checkShelfCapacity(shelfCapacity, shelfManager, orderMonitor, courierDispatcher, Some(schedule))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  private def suspend(delayInMillis: Int, shelfManager: ActorRef, orderMonitor: ActorRef, dispatcher: ActorRef): Unit = {
    context.parent ! ComponentState(KitchenActor, UnhealthyButOperational, Some(self), 0.5f)
    context.become(suspendIncomingOrders(resume(self, delayInMillis), shelfManager, orderMonitor, dispatcher))
  }

  private def resume(kitchen: ActorRef, pauseInMillis: Int): Cancellable = {
    context.system.scheduler.scheduleOnce(pauseInMillis millis) {
      kitchen ! AttemptToResumeTakingOrders
    }
  }

  private def becomeActive(shelfManager: ActorRef, orderMonitor: ActorRef, dispatcher: ActorRef): Unit = {
    shelfManager.tell(ReportStatus, sender())
    sender() ! ComponentState(KitchenActor, Operational, Some(self), 1f)
    context.become(active(shelfManager, orderMonitor, dispatcher))
  }

  private def checkShelfCapacity(shelfCapacity: CapacityUtilization, shelfManager: ActorRef, orderMonitor: ActorRef, courierDispatcher: ActorRef, prevSchedule: Option[Cancellable] = None): Unit = {
    prevSchedule.foreach(_.cancel())
    if (shelfCapacity.overflow > OverflowUtilizationSafetyThreshold) {
      log.debug(s"Shelf overflow capacity utilization exceeds max threshold: ${shelfCapacity.overflow}. Will temporarily pause processing orders.")
      suspend(DelayInMillisIfOverflowIsAboveThreshold, shelfManager, orderMonitor, courierDispatcher)
    }
    else {
      log.debug(s"Shelf overflow capacity utilization is below max threshold: ${shelfCapacity.overflow}. Will resume taking orders.")
      unstashAll()
      becomeActive(shelfManager, orderMonitor, courierDispatcher)
    }
  }


}
