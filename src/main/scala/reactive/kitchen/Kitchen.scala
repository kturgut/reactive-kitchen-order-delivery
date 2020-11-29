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

  def props(name: String) = Props(new Kitchen(name))

  case class InitializeKitchen(orderMonitor: ActorRef,
                               dispatcher: ActorRef) extends JacksonSerializable

  case object EvaluateState


  case class ReadyForService(kitchenName: String, expectedOrdersPerSecond: Int, kitchenRef: ActorRef,
                             shelfManagerRef: ActorRef) extends JacksonSerializable


  case class ActiveState(shelfManager: ActorRef,
                         orderMonitor: ActorRef,
                         dispatcher: ActorRef,
                         courierAvailability:CourierAvailability,
                         shelfCapacity:CapacityUtilization,
                         numberOfOrdersBeforeSuspension:Int) extends JacksonSerializable {
    def toSuspended(schedule:Cancellable):SuspendedState =
      SuspendedState(schedule,shelfManager,orderMonitor,dispatcher,courierAvailability,shelfCapacity)
    def toSuspended(schedule:Cancellable, availability: CourierAvailability):SuspendedState =
      SuspendedState(schedule,shelfManager,orderMonitor,dispatcher,availability,shelfCapacity)
    def toSuspended(schedule:Cancellable, shelfCapacity: CapacityUtilization):SuspendedState =
      SuspendedState(schedule,shelfManager,orderMonitor,dispatcher,courierAvailability,shelfCapacity)
    override def toString:String = s"Suspended  with courier availability:${courierAvailability} " +
        s"and shelf utilization:${shelfCapacity}, nOrdersBeforeSuspension:$numberOfOrdersBeforeSuspension"
  }

  case class SuspendedState(schedule: Cancellable,
                            shelfManager: ActorRef,
                            orderMonitor: ActorRef,
                            dispatcher: ActorRef,
                            courierAvailability:CourierAvailability,
                            shelfCapacity:CapacityUtilization,
                            counter:Int=0) extends JacksonSerializable {
    def toActive(numberOfOrdersBeforeSuspension:Int):ActiveState =
      ActiveState(shelfManager,orderMonitor,dispatcher,courierAvailability,shelfCapacity,numberOfOrdersBeforeSuspension)
    override def toString:String = s"Suspended  with courier availability:${courierAvailability} " +
      s"and shelf utilization:${shelfCapacity}, counter:$counter"
  }

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
          val shelfManager = context.actorOf(ShelfManager.props(self, orderMonitor, dispatcher), ShelfManagerActor)
          context.watch(shelfManager)
          shelfManager.tell(state.update(ComponentState(ShelfManagerActor, Operational, Some(shelfManager))), sender())
          dispatcher ! RecruitCouriers(dispatcherConf.NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
          becomeActive(ActiveState(shelfManager, orderMonitor, dispatcher,
            CourierAvailability(100,100), CapacityUtilization(0,0,10), kitchenConf.MaxNumberOfOrdersBeforeSuspension))
      }

    case ReportStatus => sender() ! ComponentState(KitchenActor, ComponentState.Initializing, Some(self))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  def active(state:ActiveState): Receive = {

    case order: Order =>
      val product = PackagedProduct(order, courierConf.deliveryWindow)
      log.info(s"Kitchen $name prepared order for '${order.name}' id:${order.id}. Sending it to ShelfManager for courier pickup")
      state.shelfManager ! product
      state.dispatcher ! product
      state.orderMonitor ! product
      if (state.numberOfOrdersBeforeSuspension<=1) {
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

    case other => log.error(s"Received unrecognized message $other while open from sender: ${sender()}")

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
  def suspended(state:SuspendedState): Receive = {
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
      becomeSuspended(state.copy(schedule=reminderToResume(kitchenConf.SuspensionTimer),shelfCapacity=shelfCapacity))

    case availability@CourierAvailability(available, _) =>
      log.debug(s"Kitchen received courier availability: $available")
      self ! EvaluateState
      becomeSuspended(state.copy(schedule=reminderToResume(kitchenConf.SuspensionTimer),courierAvailability=availability))

    case assignment: CourierAssignment =>
      log.debug(s"Forwarding courier assignment to shelf for id:${assignment.order.id} by ${assignment.courierName}")
      state.shelfManager ! assignment


    case other => log.error(s"Received unrecognized message $other while suspended from sender: ${sender()}")
  }



  private def becomeSuspended(state:SuspendedState): Unit = {
    log.debug(state.toString())
    context.parent ! ComponentState(KitchenActor, UnhealthyButOperational, Some(self), 0.5f)
    context.become(suspended(state.copy(counter=state.counter+1)))
  }

  private def becomeActive(state:ActiveState): Unit = {
    log.debug(state.toString())
    context.parent ! ComponentState(KitchenActor, Operational, Some(self), 1f)
    context.become(active(state))
  }

  private def reminderToResume(pauseDuration: FiniteDuration): Cancellable = {
    context.system.scheduler.scheduleOnce(pauseDuration) {
      self ! EvaluateState
    }
  }

  private def attemptToResume(state:SuspendedState): Unit = {
    state.schedule.cancel()
    if (state.shelfCapacity.overflow > shelfManagerConf.OverflowUtilizationSafetyThreshold) {
      log.debug(s"Shelf overflow capacity utilization ${state.shelfCapacity.overflow} exceeds max threshold: " +
        s"${shelfManagerConf.OverflowUtilizationSafetyThreshold}. Will temporarily pause processing orders.")
      becomeSuspended(state.copy(schedule=reminderToResume(kitchenConf.SuspensionTimer)))
    } else if (state.courierAvailability.available == 0) {
      log.debug(s"Courier availability is at ${state.courierAvailability.available}. Will temporarily pause processing orders.")
      becomeSuspended(state.copy(schedule=reminderToResume(kitchenConf.SuspensionTimer)))
    }
    else {
      log.debug(s"Resume operations: overflow shelf utilization is " +
        s"${state.shelfCapacity.overflow}  and courier availability is ${state.courierAvailability.available}")
      unstashAll()
      becomeActive(state.toActive(math.min(state.shelfCapacity.overflowAvailableSpace, kitchenConf.MaxNumberOfOrdersBeforeSuspension)))
    }
  }


}
