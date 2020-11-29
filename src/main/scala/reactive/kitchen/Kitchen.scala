package reactive.kitchen

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Stash, Terminated, Timers}
import reactive.config.Configs
import reactive.coordinator.ComponentState.{Operational, UnhealthyButOperational}
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.CourierAssignment
import reactive.delivery.Dispatcher.{CourierAvailability, DeclineCourierRequest, RecruitCouriers, ReportAvailability}
import reactive.order.Order
import reactive.storage.ShelfManager.CapacityUtilization
import reactive.storage.{PackagedProduct, ShelfManager}
import reactive.{JacksonSerializable, KitchenActor, ShelfManagerActor}

import scala.concurrent.duration.FiniteDuration
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

  def props(name: String, expectedOrdersPerSecond: Int) = Props(new Kitchen(name))

  case class InitializeKitchen(orderMonitor: ActorRef,
                               dispatcher: ActorRef) extends JacksonSerializable

  case object EvaluateState


  case class ReadyForService(kitchenName: String, expectedOrdersPerSecond: Int, kitchenRef: ActorRef,
                             shelfManagerRef: ActorRef) extends JacksonSerializable

}


class Kitchen(name: String) extends Actor with ActorLogging with Timers with Stash with Configs {

  import Kitchen._
  import akka.pattern.ask
  import reactive.system.dispatcher

  implicit val timeout = kitchenConf.timeout

  override val receive: Receive = closedForService

  def closedForService: Receive = {
    case state: SystemState =>
      (state.dispatcherOption, state.orderMonitorOption) match {
        case (Some(dispatcher), Some(orderMonitor)) =>
          log.debug(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with ${dispatcher} and ${orderMonitor}")
          val shelfManager = context.actorOf(ShelfManager.props(self, orderMonitor, dispatcher), ShelfManagerActor)
          context.watch(shelfManager)
          shelfManager.tell(state.update(ComponentState(ShelfManagerActor, Operational, Some(shelfManager))), sender())
          dispatcher ! RecruitCouriers(dispatcherConf.NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
          becomeActive(shelfManager, orderMonitor, dispatcher, CourierAvailability(100,100), CapacityUtilization(0,0,10))
      }

    case ReportStatus => sender() ! ComponentState(KitchenActor, ComponentState.Initializing, Some(self))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  def active(shelfManager: ActorRef,
             orderMonitor: ActorRef,
             courierDispatcher: ActorRef,
             courierAvailability:CourierAvailability,
             shelfCapacity:CapacityUtilization): Receive = {

    case order: Order =>
      val product = PackagedProduct(order, courierConf.deliveryWindow)
      log.info(s"Kitchen $name prepared order for '${order.name}' id:${order.id}. Sending it to ShelfManager for courier pickup")
      shelfManager ! product
      courierDispatcher ! product
      orderMonitor ! product

    case availability@CourierAvailability(available, _) =>
      log.debug(s"Kitchen received courier availability: $available")
      if (available == 0) {
        becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher,availability,shelfCapacity)
      }

    case assignment: CourierAssignment =>
      log.debug(s"Forwarding courier assignment to shelf for ${assignment.order.id} by ${assignment.courierName}")
      shelfManager ! assignment

    case decline@DeclineCourierRequest(_, availability) =>
      shelfManager ! decline
      self ! availability

    case shelfCapacity: CapacityUtilization =>
      if (shelfCapacity.overflow > shelfManagerConf.OverflowUtilizationSafetyThreshold) {
        becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher, courierAvailability, shelfCapacity)
      }

    case ReportStatus =>
      sender() ! ComponentState(KitchenActor, Operational, Some(self))

    case Terminated(ref) =>
      log.info(s"Shelf Manager ${ref.path} is terminated")
      context.become(closedForService)

    case other => log.error(s"Received unrecognized message $other while open from sender: ${sender()}")

    case EvaluateState =>
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
  def suspended(schedule: Cancellable,
                shelfManager: ActorRef,
                orderMonitor: ActorRef,
                courierDispatcher: ActorRef,
                courierAvailability:CourierAvailability,
                shelfCapacity:CapacityUtilization
               ): Receive = {
    case order: Order =>
      log.debug(s"Kitchen stashing message received in suspended state: $order")
      stash()

    case decline@DeclineCourierRequest(_, availability) =>
      shelfManager ! decline
      self ! availability

    case EvaluateState =>
      attemptToResume(schedule:Cancellable, shelfCapacity, shelfManager, orderMonitor, courierDispatcher,courierAvailability)

    case shelfCapacity: CapacityUtilization =>
      self ! EvaluateState
      becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher,courierAvailability,shelfCapacity)

    case availability@CourierAvailability(available, _) =>
      log.debug(s"Kitchen received courier availability: $available")
      self ! EvaluateState
      becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher,availability,shelfCapacity)

    case assignment: CourierAssignment =>
      log.debug(s"Forwarding courier assignment to shelf for ${assignment.order.id} by ${assignment.courierName}")
      shelfManager ! assignment


    case other => log.error(s"Received unrecognized message $other while suspended from sender: ${sender()}")
  }

  var suspensionCounter = 0

  private def becomeSuspended(schedule: Cancellable,
                              shelfManager: ActorRef,
                              orderMonitor: ActorRef,
                              dispatcher: ActorRef,
                              courierAvailability:CourierAvailability,
                              shelfCapacity:CapacityUtilization
                             ): Unit = {
    suspensionCounter += 1
    log.debug(s"KITCHEN SUSPENDED $suspensionCounter when $courierAvailability and $shelfCapacity")
    context.parent ! ComponentState(KitchenActor, UnhealthyButOperational, Some(self), 0.5f)
    context.become(suspended(schedule, shelfManager, orderMonitor, dispatcher,courierAvailability,shelfCapacity))
  }

  private def becomeActive(shelfManager: ActorRef, orderMonitor: ActorRef, dispatcher: ActorRef,courierAvailability: CourierAvailability,shelfCapacity: CapacityUtilization): Unit = {
    suspensionCounter = 0
    log.debug(s"KITCHEN ACTIVE when $courierAvailability and $shelfCapacity")
    context.parent ! ComponentState(KitchenActor, Operational, Some(self), 1f)
    context.become(active(shelfManager, orderMonitor, dispatcher,courierAvailability,shelfCapacity))
  }

  private def reminderToResume(pauseDuration: FiniteDuration): Cancellable = {
    context.system.scheduler.scheduleOnce(pauseDuration) {
      self ! EvaluateState
    }
  }

  private def attemptToResume(schedule:Cancellable, shelfCapacity: CapacityUtilization,
                                 shelfManager: ActorRef,
                                 orderMonitor: ActorRef,
                                 courierDispatcher: ActorRef,
                                 courierAvailability: CourierAvailability
                                ): Unit = {
    schedule.cancel()
    if (shelfCapacity.overflow > shelfManagerConf.OverflowUtilizationSafetyThreshold) {
      log.debug(s"Shelf overflow capacity utilization ${shelfCapacity.overflow} exceeds max threshold: " +
        s"${shelfManagerConf.OverflowUtilizationSafetyThreshold}. Will temporarily pause processing orders.")
      becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher,courierAvailability,shelfCapacity)
    } else if (courierAvailability.available == 0) {
      log.debug(s"Courier availability is at ${courierAvailability.available}. Will temporarily pause processing orders.")
      becomeSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfManager, orderMonitor, courierDispatcher,courierAvailability,shelfCapacity)
    }
    else {
      log.debug(s"Resume operations: overflow shelf utilization is ${shelfCapacity.overflow}  and courier availability is ${courierAvailability.available}")
      unstashAll()
      becomeActive(shelfManager, orderMonitor, courierDispatcher,courierAvailability,shelfCapacity)
    }
  }


}
