package reactive.kitchen

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Stash, Terminated, Timers}
import reactive.config.Configs
import reactive.coordinator.ComponentState.{Operational, UnhealthyButOperational}
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.CourierAssignment
import reactive.delivery.Dispatcher.{CourierAvailability, DeclineCourierRequest, RecruitCouriers}
import reactive.order.Order
import reactive.storage.ShelfManager.CapacityUtilization
import reactive.storage.{PackagedProduct, ShelfManager}
import reactive.{JacksonSerializable, KitchenActor, ShelfManagerActor}

import scala.concurrent.duration.FiniteDuration

// @formatter:off
/**
 * Kitchen is a Stateless Actor. Parent Actor is Coordinator.
 * Kitchen receives Orders from OrderProcessor and prepares PackagedProduct.
 * PackagedProducts are sent to ShelfManager for storage until they are picked up by a Courier or discarded.
 *
 * Kitchen can be in one of two states at any one time:
 *   1- closedForService
 *   2- active => Kitchen processes fixed number of orders at a single time based before it briefly (100mls) goes to suspended
 *                state to ensure that the ShelfManager and Dispatcher availability information is received.
 *                Any stashed messages received while in suspended state will be processed in active state in order received
 *   3- suspended =>
 *                While in suspended state, kitchen receives updates from ShelfManager and Dispatcher regarding their availability
 *                If these are below desired thresholds, kitchen remains in suspended state and stashes incoming Orders to be processed later
 *
 * Timers
 *   attempToResume: kicksIn every 100mls while in suspended state.
 *
 * Kitchen handles the following incoming messages
 *   when closedForService:
 *        SystemState (with Dispatcher and OrderMonitor actors) =>
 *             This initializes the Kitchen as it creates ShelfManager as a child actor.
 *    when active:
 *        Order =>
 *             Prepare the order and then create a PackagedProduct
 *             Creation of PackagedProduct is sent to OrderMonitor as an OrderLifeCycle event.
 *             Also send the PackagedProduct to ShelfManager for storage.
 *             Also notify CourierDispatcher that a new PackagedProduct is created and it should allocate a Courier for delivery
 *             If numberOfOrders before suspend = 0, suspend
 *        CourierAvailability => Update state with new CourierAvailability info. If availability below threshold suspend
 *        CapacityUtilization => Update state with new CapacityUtiliaztion info. If availability below threshold suspend
 *        CourierAssignment => Forward to ShelfManager
 *        DeclineCourierRequest => Update state with new courier availability info and stash the order to be reprocessed later
 *    when suspended:
 *        Order => stash
 *        EvaluateState => If availability of Dispatcher and ShelfManager are above desired thresholds, become active
 *        CourierAvailability => Update state with new CourierAvailability info. EvaluateState
 *        CapacityUtilization => Update state with new CapacityUtilization info. EvaluateState
 *        CourierAssignment =>  Forward to ShelfManager
 *        DeclineCourierRequest => Update state with new courier availability info and stash the order to be reprocessed later
 *
 */
// @formatter:on

object Kitchen {
  val TurkishCousine = "Turkish"
  val AmericanCousine = "American"

  def props(name: String) = Props(new Kitchen(name))

  case class InitializeKitchen(orderMonitor: ActorRef,
                               dispatcher: ActorRef) extends JacksonSerializable

  trait KitchenState extends JacksonSerializable
  case class ReadyForService(kitchenName: String, expectedOrdersPerSecond: Int, kitchenRef: ActorRef,
                             shelfManagerRef: ActorRef) extends KitchenState

  case class ActiveState(shelfManager: ActorRef,
                         orderMonitor: ActorRef,
                         dispatcher: ActorRef,
                         courierAvailability: CourierAvailability,
                         shelfCapacity: CapacityUtilization,
                         numberOfOrdersBeforeSuspension: Int) extends KitchenState {
    def toSuspended(schedule: Cancellable): SuspendedState =
      SuspendedState(schedule, shelfManager, orderMonitor, dispatcher, courierAvailability, shelfCapacity)

    def toSuspended(schedule: Cancellable, availability: CourierAvailability): SuspendedState =
      SuspendedState(schedule, shelfManager, orderMonitor, dispatcher, availability, shelfCapacity)

    def toSuspended(schedule: Cancellable, shelfCapacity: CapacityUtilization): SuspendedState =
      SuspendedState(schedule, shelfManager, orderMonitor, dispatcher, courierAvailability, shelfCapacity)

    override def toString: String = s"Suspended  with courier availability:${courierAvailability} " +
      s"and shelf utilization:${shelfCapacity}, nOrdersBeforeSuspension:$numberOfOrdersBeforeSuspension"
  }

  case class SuspendedState(schedule: Cancellable,
                            shelfManager: ActorRef,
                            orderMonitor: ActorRef,
                            dispatcher: ActorRef,
                            courierAvailability: CourierAvailability,
                            shelfCapacity: CapacityUtilization,
                            counter: Int = 0) extends JacksonSerializable {
    def toActive(numberOfOrdersBeforeSuspension: Int): ActiveState =
      ActiveState(shelfManager, orderMonitor, dispatcher, courierAvailability, shelfCapacity, numberOfOrdersBeforeSuspension)

    override def toString: String = s"Suspended  with courier availability:${courierAvailability} " +
      s"and shelf utilization:${shelfCapacity}, counter:$counter"
  }

  case object EvaluateState

}


class Kitchen(name: String) extends Actor with ActorLogging with Timers with Stash with Configs {

  import Kitchen._
  import reactive.system.dispatcher

  implicit val timeout = kitchenConf.timeout

  override val receive: Receive = closedForService

  def closedForService: Receive = {
    case state: SystemState =>
      (state.dispatcherOption, state.orderMonitorOption) match {
        case (Some(dispatcher), Some(orderMonitor)) =>
          log.debug(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with ${dispatcher} and ${orderMonitor}")
          val shelfManager = context.actorOf(
            ShelfManager.props(self, orderMonitor, dispatcher).withDispatcher(shelfManagerConf.SystemDispatcherConfigPath), ShelfManagerActor)
          context.watch(shelfManager)
          shelfManager.tell(state.update(ComponentState(ShelfManagerActor, Operational, Some(shelfManager))), sender())
          dispatcher ! RecruitCouriers(dispatcherConf.NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
          becomeActive(ActiveState(shelfManager, orderMonitor, dispatcher,
            CourierAvailability(100, 100), CapacityUtilization(0, 0, 10), kitchenConf.MaxNumberOfOrdersBeforeSuspension))
      }

    case ReportStatus => sender() ! ComponentState(KitchenActor, ComponentState.Initializing, Some(self))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  def active(state: ActiveState): Receive = {

    case order: Order =>
      val product = PackagedProduct(order, courierConf.deliveryWindow)
      log.info(s"Kitchen $name prepared order for '${order.name}' id:${order.id}. Sending it to ShelfManager for courier pickup")
      state.shelfManager ! product
      state.dispatcher ! product
      state.orderMonitor ! product
      if (state.numberOfOrdersBeforeSuspension <= 1) {
        becomeSuspended(state.toSuspended(reminderToResume(kitchenConf.SuspensionTimer)))
      } else {
        becomeActive(state.copy(numberOfOrdersBeforeSuspension = state.numberOfOrdersBeforeSuspension - 1))
      }

    case availability@CourierAvailability(available, _) =>
      log.debug(s"Kitchen received courier availability: $available")
      if (available == 0) {
        becomeSuspended(state.toSuspended(reminderToResume(kitchenConf.SuspensionTimer), availability))
      }

    case assignment: CourierAssignment =>
      log.debug(s"Forwarding courier assignment to shelf for id:${assignment.order.id} by ${assignment.courierName}")
      state.shelfManager ! assignment

    case decline@DeclineCourierRequest(_, availability) =>
      state.shelfManager ! decline
      self ! availability

    case shelfCapacity: CapacityUtilization =>
      if (shelfCapacity.overflow > shelfManagerConf.OverflowUtilizationSafetyThreshold) {
        becomeSuspended(state.toSuspended(reminderToResume(kitchenConf.SuspensionTimer), shelfCapacity))
      }

    case ReportStatus =>
      sender() ! ComponentState(KitchenActor, Operational, Some(self))

    case Terminated(ref) =>
      log.info(s"Shelf Manager ${ref.path} is terminated")
      context.become(closedForService)

    case EvaluateState =>

    case other =>
      log.error(s"Received unrecognized message $other while open from sender: ${sender()}")

  }


  /**
   * If overflow capacity utilization exceeds OverflowUtilizationSafetyThreshold is received or CourierAvailability is zero
   * Kitchen temporarily suspends processing incoming orders, evaluating the status every 100mls
   */
  def suspended(state: SuspendedState): Receive = {
    case order: Order =>
      log.debug(s"Kitchen stashing message received in suspended state: $order")
      stash()

    case decline@DeclineCourierRequest(_, availability) =>
      state.shelfManager ! decline
      self ! availability

    case EvaluateState =>
      attemptToResume(state)

    case shelfCapacity: CapacityUtilization =>
      self ! EvaluateState
      becomeSuspended(state.copy(schedule = reminderToResume(kitchenConf.SuspensionTimer), shelfCapacity = shelfCapacity))

    case availability@CourierAvailability(available, _) =>
      log.debug(s"Kitchen received courier availability: $available")
      self ! EvaluateState
      becomeSuspended(state.copy(schedule = reminderToResume(kitchenConf.SuspensionTimer), courierAvailability = availability))

    case assignment: CourierAssignment =>
      log.debug(s"Forwarding courier assignment to shelf for id:${assignment.order.id} by ${assignment.courierName}")
      state.shelfManager ! assignment


    case other => log.error(s"Received unrecognized message $other while suspended from sender: ${sender()}")
  }


  private def becomeSuspended(state: SuspendedState): Unit = {
    log.debug(state.toString())
    context.parent ! ComponentState(KitchenActor, UnhealthyButOperational, Some(self), 0.5f)
    context.become(suspended(state.copy(counter = state.counter + 1)))
  }

  private def becomeActive(state: ActiveState): Unit = {
    log.debug(state.toString())
    context.parent ! ComponentState(KitchenActor, Operational, Some(self), 1f)
    context.become(active(state))
  }

  private def reminderToResume(pauseDuration: FiniteDuration): Cancellable = {
    context.system.scheduler.scheduleOnce(pauseDuration) {
      self ! EvaluateState
    }
  }

  private def attemptToResume(state: SuspendedState): Unit = {
    state.schedule.cancel()
    if (state.shelfCapacity.overflow > shelfManagerConf.OverflowUtilizationSafetyThreshold) {
      log.debug(s"Shelf overflow capacity utilization ${state.shelfCapacity.overflow} exceeds max threshold: " +
        s"${shelfManagerConf.OverflowUtilizationSafetyThreshold}. Will temporarily pause processing orders.")
      becomeSuspended(state.copy(schedule = reminderToResume(kitchenConf.SuspensionTimer)))
    } else if (state.courierAvailability.available == 0) {
      log.debug(s"Courier availability is at ${state.courierAvailability.available}. Will temporarily pause processing orders.")
      becomeSuspended(state.copy(schedule = reminderToResume(kitchenConf.SuspensionTimer)))
    }
    else {
      log.debug(s"Resume operations: overflow shelf utilization is " +
        s"${state.shelfCapacity.overflow}  and courier availability is ${state.courierAvailability.available}")
      unstashAll()
      becomeActive(state.toActive(math.min(state.shelfCapacity.overflowAvailableSpace, kitchenConf.MaxNumberOfOrdersBeforeSuspension)))
    }
  }


}
