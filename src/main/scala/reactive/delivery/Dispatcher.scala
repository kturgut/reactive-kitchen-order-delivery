package reactive.delivery

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.routing._
import reactive.config.DispatcherConfig
import reactive.coordinator.ComponentState.{Initializing, Operational, State, UnhealthyButOperational}
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.storage.PackagedProduct
import reactive.{DispatcherActor, JacksonSerializable}

// @formatter:off
/**
 * Dispatcher is a Stateless Actor. Parent Actor is Coordinator.
 * Dispatcher routes PackagedProducts sent from ShelfManager to Couriers.
 * Couriers after receiving the PackagedProduct info respond directly to sender as well as Dispatcher with CourierAssignment
 *
 * Dispatcher can be in one of two states at any one time
 *   1- closedForService
 *   2- active
 *
 * CourierDispatcher handles the following incoming messages
 *   when closedForService:
 *      SystemState (with ShelfManager and OrderMonitor actors) =>
 *          This initializes the Dispatcher as it creates Couriers and establishes a RoundRobin route
 *          as it transitions to Active state.
 *          To model after real-life scenario: Couriers are created in batches, up to a maximum number of couriers.
 *          Both batch size as well as max couriers are controlled by the Config.
 *          If Messages are sent to CourierDispatcher before KitchenReadyForService is received, these messages are
 *          stashed in mailbox to be replayed right after the initialization is complete.
 *   when active:
 *       PackagedProduct =>
 *          PackagedProduct gets routed to a single Courier in RoundRobin fashion using SmallestMailboxRoutingLogic.
 *          If no Courier is available, Dispatcher responds with DeclineCourierRequest to sender.
 *       Available =>
 *          Courier notifies Dispatcher when it becomes available after completing delivery and gets added as a Routee
 *       CourierAssignment =>
 *          Courier is removed from the router as a Routee
 *       DeclineCourierAssignment =>
 *          Courier may choose to decline the assignment, in such case, it is removed from router.
 *       Available =>
 *          Courier is added to the router as a Routee when available.
 *       Terminated =>
 *           Since Dispatcher is a supervisor of Courier it watches the transitions in its lifecycle. It will replace a dead Courier
 */
// @formatter:on


object Dispatcher {

  case class RecruitCouriers(numberOfCouriers: Int, shelfManager: ActorRef, orderMonitor: ActorRef) extends JacksonSerializable

  case class CourierAvailability(available: Int, total: Int) extends JacksonSerializable {
    def state(config: DispatcherConfig): State = if (health < config.MinimumAvailableToRecruitedCouriersRatio) UnhealthyButOperational else Operational

    def health: Float = available.toFloat / total
  }

  case class DeclineCourierRequest(product: PackagedProduct, availability: CourierAvailability) extends JacksonSerializable

  case object ReportAvailability

}

class Dispatcher extends Actor with Stash with ActorLogging {

  import Courier._
  import Dispatcher._

  val config = DispatcherConfig(context.system)

  override val receive: Receive = closedForService

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

    case product: PackagedProduct =>
      val availability = CourierAvailability(router.routees.size, lastCourierId)
      if (availability.available > 0) {
        log.debug(s"Dispatcher routing order with id:${product.order.id}. " +
          s"Total available couriers ${availability.available}/$lastCourierId.") // Available: ${available(router)}")
        router.route(product, sender())
      }
      else {
        log.warning(s"Dispatcher courier availability is:$availability. Declining delivery of order with id:${product.order.id}.")
        sender() ! DeclineCourierRequest(product, availability)
      }

    case CourierAssignment(order, courierName, courierRef, _) =>
      log.info(s"Courier ${courierName} is now on assignment of id:${order.id}. Total available couriers ${router.routees.size}/$lastCourierId")
      becomeActivate(orderMonitor, shelfManager, router.removeRoutee(courierRef), lastCourierId)

    case Available(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now available. Total available couriers ${router.routees.size}/$lastCourierId")
      context.become(active(orderMonitor, shelfManager, router.addRoutee(courierRef), lastCourierId))

    // reroute if courier declines
    case DeclineCourierAssignment(name, courierRef, product, originalSender) =>
      log.debug(s"Courier $name declined assignment to ${product.order.id}, originalSender: ${originalSender}")
      self.tell(product, originalSender)
      becomeActivate(orderMonitor, shelfManager, router.removeRoutee(courierRef), lastCourierId)

    case Terminated(ref) =>
      log.warning(s"Courier '${ref.path.name}' is terminated!")
      val newCourier = context.actorOf(
        Courier.props(s"Replacement for ${ref.path.name})", orderMonitor, shelfManager), s"${ref.path.name}_replacement")
      context.watch(newCourier)
      becomeActivate(orderMonitor, shelfManager, router.addRoutee(newCourier).removeRoutee(ref), lastCourierId)

    case ReportStatus =>
      sender() ! ComponentState(DispatcherActor, CourierAvailability(router.routees.size, lastCourierId).state(config), Some(self))

    case ReportAvailability =>
      sender() ! CourierAvailability(router.routees.size, lastCourierId)

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
    if (availability.health < config.MinimumAvailableToRecruitedCouriersRatio) {
      log.warning(s"Recommended to increase number of couriers or slow down order processing: ${router.routees.size}/$lastCourierId")
    }
    context.become(active(orderMonitor, shelfManager, router, lastCourierId))
  }

  /**
   * Use it to broadcast messages to all couriers at once
   */
  def asBroadcastRouter(router: Router) = router.copy(logic = BroadcastRoutingLogic())

  def recruitCouriers(demand: RecruitCouriers, lastCourierId: Int = 0, oldRouter: Router) = {
    val finalCourierId = math.min(lastCourierId + demand.numberOfCouriers, config.MaximumNumberOfCouriers)
    val newSlaves = for (id <- lastCourierId + 1 to finalCourierId) yield {
      val courier = context.actorOf(Courier.props(s"Courier_$id", demand.orderMonitor, demand.shelfManager), s"Courier_$id")
      context.watch(courier)
      ActorRefRoutee(courier)
    }
    val router = Router(SmallestMailboxRoutingLogic(), oldRouter.routees ++ newSlaves)
    log.info(s"Dispatcher ready for service with ${router.routees.size} couriers out of $finalCourierId total!")
    unstashAll()
    context.become(active(demand.orderMonitor, demand.shelfManager, router, finalCourierId))
  }

  def available(router: Router): String = {
    router.routees.mkString(",")
  }

}

