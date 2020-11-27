package reactive.delivery

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.routing.{ActorRefRoutee, BroadcastRoutingLogic, RoundRobinRoutingLogic, Routee, Router}
import akka.serialization.jackson.JacksonObjectMapperProviderSetup
import reactive.{DispatcherActor, JacksonSerializable}
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

  case class RecruitCouriers(numberOfCouriers: Int, shelfManager: ActorRef, orderMonitor: ActorRef) extends JacksonSerializable

  case class CourierAvailability(available: Int, total: Int) extends JacksonSerializable {
    def health:Float = available.toFloat / total
    def state(config:DispatcherConfig): State = if (health < config.MinimumAvailableToRecruitedCouriersRatio) UnhealthyButOperational else Operational
  }
  case class DeclineCourierRequest(product:PackagedProduct, availability:CourierAvailability) extends JacksonSerializable
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
      recruitCouriers(recruitOrder, 0, Router(RoundRobinRoutingLogic()))

    case _ =>
      log.info(s"CourierDispatcher is not active. Stashing all messages")
      stash()
  }

  def active(orderMonitor: ActorRef, shelfManager: ActorRef, router: Router, lastCourierId: Int): Receive = {
    case Terminated(ref) =>
      log.warning(s"Courier '${ref.path.name}' is terminated!")
      val newCourier = context.actorOf(
        Courier.props(s"Replacement for ${ref.path.name})", orderMonitor, shelfManager), s"${ref.path.name}_replacement")
      context.watch(newCourier)
      becomeActivate(orderMonitor, shelfManager, router.addRoutee(newCourier).removeRoutee(ref), lastCourierId)


    case assignment:CourierAssignment =>
      log.info(s"Courier ${assignment.courierName} is now on assignment. Total available couriers ${router.routees.size}/$lastCourierId")
      becomeActivate(orderMonitor, shelfManager, router.removeRoutee(assignment.courierRef), lastCourierId)

    case Available(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now available. Total available couriers ${router.routees.size}/$lastCourierId")
      context.become(active(orderMonitor, shelfManager, router.addRoutee(courierRef), lastCourierId))

    // reroute if courier declines
    case DeclineCourierAssignment(courierRef, product, originalSender) =>
      log.debug(s"Courier declined $courierRef assignment to $product.")
      val newRouter = router.removeRoutee(courierRef)
      newRouter.route(product, originalSender)
      becomeActivate(orderMonitor, shelfManager, newRouter, lastCourierId)

    case ReportStatus =>
      sender() ! ComponentState(DispatcherActor, CourierAvailability(router.routees.size, lastCourierId).state(config), Some(self))

    case ReportAvailability =>
      sender() ! CourierAvailability(router.routees.size, lastCourierId)

    case product: PackagedProduct =>
      val availability = CourierAvailability(router.routees.size, lastCourierId)
      if (availability.available > 0) {
        log.debug(s"Dispatcher routing order with id:${product.order.id}. Total available couriers ${availability.available}/$lastCourierId.") // Available: ${available(router)}")
        router.route(product, sender())
      }
      else {
        log.warning(s"Dispatcher does not have available routers. Declining delivery order. $availability")
        sender() ! DeclineCourierRequest(product,availability)
      }


    case recruitOrder: RecruitCouriers =>
      recruitCouriers(recruitOrder, lastCourierId, router)

    case message =>
      log.error(s"Unrecognized message received from $sender. The message: $message")

  }

  def becomeActivate(orderMonitor: ActorRef, shelfManager: ActorRef, router: Router, lastCourierId: Int) = {
    val availability = CourierAvailability(router.routees.size, lastCourierId)
    if (availability.available == 0 && availability.total < config.MaximumNumberOfCouriers) {
      self ! RecruitCouriers(config.NumberOfCouriersToRecruitInBatches, shelfManager, orderMonitor)
    }
    if (availability.health < config.MinimumAvailableToRecruitedCouriersRatio)
      context.parent ! availability
    context.become(active(orderMonitor, shelfManager, router,lastCourierId))
  }

  /**
   * Use it to broadcast messages to all couriers at once
   */
  def asBroadcastRouter(router: Router) = router.copy(logic = BroadcastRoutingLogic())

  def recruitCouriers(demand: RecruitCouriers, lastCourierId: Int = 0, oldRouter:Router) = {
    val finalCourierId = math.min(lastCourierId + demand.numberOfCouriers,config.MaximumNumberOfCouriers)
    val newSlaves = for (id <- lastCourierId + 1 to finalCourierId) yield {
      val courier = context.actorOf(Courier.props(s"Courier_$id", demand.orderMonitor, demand.shelfManager), s"Courier_$id")
      context.watch(courier)
      ActorRefRoutee(courier)
    }
    val router = Router(RoundRobinRoutingLogic(), oldRouter.routees ++ newSlaves)
    log.info(s"Dispatcher ready for service with ${router.routees.size} couriers out of $finalCourierId total!")
    unstashAll()
    context.become(active(demand.orderMonitor, demand.shelfManager, router, finalCourierId))
  }

  def available(router:Router):String = {
    router.routees.mkString(",")
  }

}

