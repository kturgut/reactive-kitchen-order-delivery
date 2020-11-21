package reactive.delivery

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.routing.{
  ActorRefRoutee,
  BroadcastRoutingLogic,
  RoundRobinRoutingLogic,
  Router
}
import reactive.kitchen.Kitchen.KitchenReadyForService
import reactive.storage.PackagedProduct

/** CourierDispatcher is a Stateless Actor. Parent Actor is CloudKitchens.
  *   CouriersDispatcher routes PackagedProducts sent from ShelfManager to Couriers.
  *   Couriers after receiving the PackagedProduct info respond directly to Shelf Manager with CourierAssignment
  *
  * CourierDispatcher can be in one of two states at any one time
  *   1- closedForService
  *   2- active
  *
  * CourierDispatcher handles the following incoming messages
  *   when closedForService:
  *      KitchenReadyForService =>
  *          This initializes the CourierDispatcher as it creates Couriers and establishes a RoundRobin route
  *          as it transitions to Active state.
  *          Currently number of Couriers to be created is chosen as a function of the incoming order throttle threshold.
  *          If Messages are sent to CourierDispatcher before KitchenReadyForService is received, these messages are
  *          stashed in mailbox to be replayed right after the initialization is complete.
  *   when active:
  *       PackagedProduct => they get routed to a single Courier in RoundRobin fashion
  *       Available => Courier notifies Dispatcher when it becomes available after completing delivery and gets added as a Routee
  *       OnAssignment => Courier notifies Dispatcher when it becomes unavailable and gets removed from the router as a Routee
  *       DeclineAssignment => Couriers can for whatever reason decline an assignment and gets removed from the router as a Routee
  *       Terminated => Since Dispatcher is a supervisor of Courier it watches the transitions in its lifecycle
  *           Hence when Dispatcher receives that one of its Couriers have been Terminated for any reason,
  *           it will replace it with another one.
  *         DiscardOrder => Send Available to CourierDispatcher, and become available.
  */

class CourierDispatcher extends Actor with Stash with ActorLogging {

  import Courier._

  override val receive: Receive = closedForService
  val MinimumNumberOfCouriersThreshold = 3

  def numberOfCouriers(maxNumberOfOrdersPerSecond: Int): Int =
    maxNumberOfOrdersPerSecond * 10

  def closedForService: Receive = {

    case KitchenReadyForService(
          _,
          expectedOrdersPerSecond,
          _,
          orderProcessorRef,
          shelfManagerRef
        ) =>
      val slaves =
        for (id <- 1 to numberOfCouriers(expectedOrdersPerSecond)) yield {
          val courier = context.actorOf(
            Courier.props(s"Courier_$id", orderProcessorRef, shelfManagerRef),
            s"Courier_$id"
          )
          context.watch(courier)
          ActorRefRoutee(courier)
        }
      val router = Router(RoundRobinRoutingLogic(), slaves)
      log.info(
        s"CourierDispatcher connected with OrderProcessor and ShelfManager. Ready for service with ${slaves.size} couriers!"
      )
      unstashAll()
      context.become(active(orderProcessorRef, shelfManagerRef, router))
    case _ =>
      log.info(s"CourierDispatcher is not active. Stashing all messages")
      stash()
  }

  def active(
    orderProcessor: ActorRef,
    shelfManager: ActorRef,
    router: Router
  ): Receive = {
    case Terminated(ref) =>
      log.warning(
        s"Courier '${ref.path.name}' is terminated, creating replacement!"
      )
      val newCourier = context.actorOf(
        Courier.props(
          s"Replacement for ${ref.path.name})",
          orderProcessor,
          shelfManager
        ),
        s"${ref.path.name}_replacement"
      )
      context.watch(newCourier)
      context.become(
        active(
          orderProcessor,
          shelfManager,
          router.addRoutee(ref).removeRoutee(ref)
        )
      )

    case OnAssignment(courierRef) =>
      log.info(
        s"Courier ${courierRef.path} is now on assignment. Removed from dispatch crew"
      )
      if (router.routees.size < MinimumNumberOfCouriersThreshold)
        log.error(
          s"Number of available couriers is below threshold: ${router.routees.size}"
        )
      context.become(
        active(orderProcessor, shelfManager, router.removeRoutee(courierRef))
      )

    case Available(courierRef) =>
      log.info(
        s"Courier ${courierRef.path} is now available. Added to dispatch crew"
      )
      context.become(
        active(orderProcessor, shelfManager, router.addRoutee(courierRef))
      )

    // reroute if courier declines
    case DeclineCourierAssignment(courierRef, product, originalSender) =>
      log.debug(s"Courier declined $courierRef assignment to $product.")
      router.route(product, originalSender)

    case product: PackagedProduct => router.route(product, sender())

    case message =>
      log.error(
        s"Unrecognized message received from ${sender()}. The message: $message"
      )
  }

  /** It is possible to broadcast messages to couriers as well if needed
    */
  def asBroadcastRouter(router: Router) =
    router.copy(logic = BroadcastRoutingLogic())
}
