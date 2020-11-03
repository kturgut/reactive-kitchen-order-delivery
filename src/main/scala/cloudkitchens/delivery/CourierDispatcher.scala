package cloudkitchens.delivery

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService
import cloudkitchens.kitchen.PackagedProduct

class CourierDispatcher extends Actor with Stash with ActorLogging {
  import Courier._

  def numberOfCouriers(maxNumberOfOrdersPerSecond:Int):Int = maxNumberOfOrdersPerSecond * 10

  override val receive:Receive = noteReadyForService

  def noteReadyForService: Receive = {

    case KitchenReadyForService(_, expectedOrdersPerSecond, _, orderProcessorRef, shelfManagerRef) =>
      val slaves = for (id <- 1 to numberOfCouriers(expectedOrdersPerSecond)) yield {
        val courier = context.actorOf(Courier.props(s"Courier_$id",orderProcessorRef, shelfManagerRef),s"Courier_$id")
        context.watch(courier)
        ActorRefRoutee(courier)
      }
      val router = Router(RoundRobinRoutingLogic(),slaves)
      log.info(s"CourierDispatcher connected with OrderProcessor and ShelfManager. Ready for service with ${slaves.size} couriers!" )
      unstashAll()
      context.become(active(orderProcessorRef, shelfManagerRef, router))
    case _ =>
      log.info(s"CourierDispatcher is not active. Stashing all messages")
      stash()
  }

  def active(orderProcessor:ActorRef, shelfManager:ActorRef, router:Router):Receive = {
    case Terminated(ref) =>
      log.warning(s"Courier '${ref.path.name}' is terminated, creating replacement!")
      val newCourier = context.actorOf(
        Courier.props(s"Replacement for ${ref.path.name})", orderProcessor,shelfManager), s"${ref.path.name}_replacement")
      context.watch(newCourier)
      context.become(active(orderProcessor, shelfManager,router.addRoutee(ref).removeRoutee(ref)))

    case OnAssignment(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now on assignment. Removed from dispatch crew")
      context.become(active(orderProcessor,shelfManager,router.removeRoutee(courierRef)))

    case Available(courierRef) =>
      log.info(s"Courier ${courierRef.path} is now available. Added to dispatch crew")
      context.become(active(orderProcessor, shelfManager,router.addRoutee(courierRef)))

    // reroute if courier declines
    case DeclineCourierAssignment(courierRef, product,originalSender) =>
      log.debug(s"Courier declined $courierRef assignment to $product. THIS SHOULD NOT HAPPEN")
      router.route(product,originalSender)

    case product:PackagedProduct => router.route(product,sender())

    case message =>
      log.warning(s"Unrecognized message received from $sender. The message: $message")
  }
}

