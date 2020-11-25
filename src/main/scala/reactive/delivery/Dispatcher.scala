package reactive.delivery

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.routing.{ActorRefRoutee, BroadcastRoutingLogic, RoundRobinRoutingLogic, Router}
import reactive.DispatcherActor
import reactive.config.DispatcherConfig
import reactive.coordinator.ComponentState.{Initializing, Operational, State, UnhealthyButOperational}
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.storage.PackagedProduct

/**
 * CourierDispatcher is a Stateless Actor. Parent Actor is CloudKitchens.
 * CouriersDispatcher routes PackagedProducts sent from ShelfManager to Couriers.
 * Couriers after receiving the PackagedProduct info respond directly to Shelf Manager with CourierAssignment
 *
 * CourierDispatcher can be in one of two states at any one time
 * 1- closedForService
 * 2- active
 *
 * CourierDispatcher handles the following incoming messages
 * when closedForService:
 * KitchenReadyForService =>
 * This initializes the CourierDispatcher as it creates Couriers and establishes a RoundRobin route
 * as it transitions to Active state.
 * Currently number of Couriers to be created is chosen as a function of the incoming order throttle threshold.
 * If Messages are sent to CourierDispatcher before KitchenReadyForService is received, these messages are
 * stashed in mailbox to be replayed right after the initialization is complete.
 * when active:
 * PackagedProduct => they get routed to a single Courier in RoundRobin fashion
 * Available => Courier notifies Dispatcher when it becomes available after completing delivery and gets added as a Routee
 * OnAssignment => Courier notifies Dispatcher when it becomes unavailable and gets removed from the router as a Routee
 * DeclineAssignment => Couriers can for whatever reason decline an assignment and gets removed from the router as a Routee
 * Terminated => Since Dispatcher is a supervisor of Courier it watches the transitions in its lifecycle
 * Hence when Dispatcher receives that one of its Couriers have been Terminated for any reason,
 * it will replace it with another one.
 * DiscardOrder => Send Available to CourierDispatcher, and become available.
 */

object Dispatcher {

  case class RecruitCouriersForKitchen(kitchenName: String, numberOfCouriers: Int, shelfManager: ActorRef, orderMonitor: ActorRef, orderProcessor: ActorRef)

  case class RecruitCouriers(numberOfCouriers: Int, shelfManager: ActorRef, orderMonitor: ActorRef)

  case class CourierAvailability(available: Int, total: Int) {
    def health = available.toFloat / total

    def state(config:DispatcherConfig): State = if (health < config.MinimumAvailableToRecruitedCouriersRatio) UnhealthyButOperational else Operational
  }

  case object ReportAvailability


}

class Dispatcher extends Actor with Stash with ActorLogging {

  import Courier._
  import Dispatcher._

  val config = DispatcherConfig(context.system)

  override val receive: Receive = closedForService

  def numberOfCouriers(maxNumberOfOrdersPerSecond: Int): Int = maxNumberOfOrdersPerSecond * 10

  def closedForService: Receive = {

    case ReportStatus =>
      sender ! ComponentState(DispatcherActor, Initializing, Some(self))

    case state: SystemState =>
      (state.shelfManagerOption, state.orderMonitorOption) match {
        case (Some(shelfManager), Some(orderMonitor)) =>
          self ! RecruitCouriers(config.NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
        case _ =>
      }

    case recruitOrder: RecruitCouriers =>
      recruitCouriers(recruitOrder, 0)

    case _ =>
      log.info(s"CourierDispatcher is not active. Stashing all messages")
      stash()
  }

  def active(orderMonitor: ActorRef, shelfManager: ActorRef, router: Router, lastCourierId: Int): Receive = {
    case Terminated(ref) =>
      log.warning(s"Courier '${ref.path.name}' is terminated, creating replacement!")
      val newCourier = context.actorOf(
        Courier.props(s"Replacement for ${ref.path.name})", orderMonitor, shelfManager), s"${ref.path.name}_replacement")
      context.watch(newCourier)
      context.become(active(orderMonitor, shelfManager, router.addRoutee(ref).removeRoutee(ref), lastCourierId))

    case OnAssignment(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now on assignment. Removed from dispatch crew. Total available couriers ${router.routees.size}")
      self.tell(ReportAvailability, context.parent)
      context.become(active(orderMonitor, shelfManager, router.removeRoutee(courierRef), lastCourierId))

    case Available(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now available. Added to dispatch crew. Total available couriers ${router.routees.size}")
      context.become(active(orderMonitor, shelfManager, router.addRoutee(courierRef), lastCourierId))

    // reroute if courier declines
    case DeclineCourierAssignment(courierRef, product, originalSender) =>
      log.debug(s"Courier declined $courierRef assignment to $product.")
      router.route(product, originalSender)

    case ReportStatus =>
      sender() ! ComponentState(DispatcherActor, CourierAvailability(router.routees.size, lastCourierId).state(config), Some(self))

    case ReportAvailability =>
      sender() ! CourierAvailability(router.routees.size, lastCourierId)

    case product: PackagedProduct =>
      log.debug(s"Dispatcher routing order with id:${product.order.id} to one of ${router.routees.size} available couriers.")
      router.route(product, sender())

    case recruitOrder: RecruitCouriers =>
      recruitCouriers(recruitOrder, lastCourierId)

    case message =>
      log.error(s"Unrecognized message received from $sender. The message: $message")

  }

  /**
   * It is possible to broadcast messages to couriers as well if needed
   */
  def asBroadcastRouter(router: Router) = router.copy(logic = BroadcastRoutingLogic())

  def recruitCouriers(demand: RecruitCouriers, lastCourierId: Int = 0) = {
    val finalCourierId = math.min(lastCourierId + demand.numberOfCouriers,config.MaximumNumberOfCouriers)
    val slaves = for (id <- lastCourierId + 1 to finalCourierId) yield {
      val courier = context.actorOf(Courier.props(s"Courier_$id", demand.orderMonitor, demand.shelfManager), s"Courier_$id")
      context.watch(courier)
      ActorRefRoutee(courier)
    }
    val router = Router(RoundRobinRoutingLogic(), slaves)
    log.info(s"CourierDispatcher ready for service with ${slaves.size} couriers out of $finalCourierId total!")
    self.tell(ReportAvailability, context.parent)
    self.tell(ReportStatus, context.parent)
    unstashAll()
    context.become(active(demand.orderMonitor, demand.shelfManager, router, finalCourierId))
  }

}

