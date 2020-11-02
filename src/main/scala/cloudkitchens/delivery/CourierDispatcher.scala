package cloudkitchens.delivery


import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Cancellable, Props, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import akka.util.Timeout
import cloudkitchens.CloudKitchens.{CourierDispatcherActorName, OrderProcessorActorName}
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService
import cloudkitchens.kitchen.PackagedProduct
import cloudkitchens.kitchen.ShelfManager.{DiscardOrder}
import cloudkitchens.order.Order
import cloudkitchens.{CloudKitchens, JacksonSerializable, system}

import scala.concurrent.duration._
import scala.util.{Failure, Success}


object Courier {

  case class CourierAssignment(order:Order,
                               courierName:String, courierRef:ActorRef, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case object DeliverNow

  case class PickupRequest(assignment:CourierAssignment, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable
  case class Pickup(product:PackagedProduct, time:LocalDateTime=LocalDateTime.now()) extends JacksonSerializable

  case class DeliveryAcceptanceRequest(order:Order) extends JacksonSerializable
  case class DeliveryAcceptance(order:Order, signature:String, tips:Int, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeclineCourierAssignment(courierRef:ActorRef, product:PackagedProduct, originalSender:ActorRef) extends JacksonSerializable

  case class DeliveryComplete(assignment:CourierAssignment, acceptance:DeliveryAcceptance, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString():String = {
      s"Delivery of product '${assignment.order.name} completed in ${(Duration.between(time, assignment.time).toMillis / 1000)%1.2f} seconds, with tip amount:${acceptance.tips}."
    }
  }
  case class Unavailable(courier:ActorRef)
  case class Available(courier:ActorRef)
  def props(name:String, orderProcessor:ActorRef, shelfManager:ActorRef) = Props(new Courier(name,orderProcessor,shelfManager))
}


class Courier(name:String,orderProcessor:ActorRef, shelfManager:ActorRef) extends Actor  with ActorLogging {
  import Courier._
  import cloudkitchens.system.dispatcher
  import akka.pattern.ask

  override val receive:Receive = available
  lazy val randomizer = new scala.util.Random(100L)
  implicit val timeout = Timeout (300 milliseconds)

  def available:Receive = {
    case product:PackagedProduct =>
      val assignment = CourierAssignment(product.order, name, self)
      log.info(s"$name received order to pickup product: ${product.order.name}")
      orderProcessor ! assignment
      shelfManager ! assignment
      context.parent ! Unavailable(self)
      context.become(onDelivery(reminderToDeliver(),assignment))

    case message => log.warning(s"Unrecognized message received from $sender(). The message: $message")
  }

  def onDelivery(scheduledAction:Cancellable, assignment: CourierAssignment):Receive = {
    case discard:DiscardOrder =>
      log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded due to ${discard.reason}")
      becomeAvailable(scheduledAction)

    case DeliverNow =>
      val future = shelfManager ? PickupRequest(assignment)
      val action = scheduledAction
      future.onComplete {
        case Success(None) =>
          log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded. Reason for cancellation unknown.")
          becomeAvailable(action)
        case Success(pickup:Pickup) =>
          (assignment.order.customer ? DeliveryAcceptanceRequest (pickup.product.order)).onComplete {
            case Success(acceptance:DeliveryAcceptance) =>
              val delivery =   DeliveryComplete(assignment,acceptance)
              log.info(delivery.prettyString)
              orderProcessor ! delivery
              becomeAvailable(action)
            case Success(message) => log.warning(s"Customer did not want to provide signature but sent this response: $message")
              becomeAvailable(action)
            case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
          }
        case Failure(exception) =>
          log.error(s"Exception received while picking up package. Canceling delivery! Exception detail: ${exception.getMessage}")
          becomeAvailable(action)
      }

    case product:PackagedProduct =>
      log.warning(s"Courier $name received pickup order while already on delivery. Declining!")
      DeclineCourierAssignment(self, product, sender())
  }

  def reminderToDeliver():Cancellable = {
    val delay = randomizer.nextFloat() * 4 + 2 // TODO read from config
    context.system.scheduler.scheduleOnce(delay seconds) {
      self ! DeliverNow
    }
  }

  private def becomeAvailable(scheduledAction:Cancellable): Unit = {
    scheduledAction.cancel()
    context.parent ! Available(self)
  }

}

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

    case Unavailable(courierRef) =>
      log.info(s"Courier with path${courierRef.path} is now unavailable. Removed from dispatch crew")
      context.become(active(orderProcessor,shelfManager,router.removeRoutee(courierRef)))

    case Available(courierRef) =>
      log.info(s"Courier with path${courierRef.path} is now available. Added to dispatch crew")
      context.become(active(orderProcessor, shelfManager,router.addRoutee(courierRef)))

    // reroute if courier declines
    case DeclineCourierAssignment(courierRef, product,originalSender) =>
      router.route(product,originalSender)

    case message => router.route(message,sender())
  }
}


object CourierDispatcherManualTest extends App {
  val root = system.actorOf(Props[CloudKitchens])
  val dispatcher = system.actorOf(Props[CourierDispatcher],CourierDispatcherActorName)
}
