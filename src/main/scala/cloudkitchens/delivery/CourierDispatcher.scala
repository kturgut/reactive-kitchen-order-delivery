package cloudkitchens.delivery


import java.util.Date

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorPath, ActorRef, Cancellable, Identify, Props, Stash, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import cloudkitchens.CloudKitchens.{CourierDispatcherActorName, OrderProcessorActorName}
import cloudkitchens.{CloudKitchens, kitchen, system}

import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration._


object Courier {

  case class Pickup(product:kitchen.Product, time:Date)
  case class DeliveryComplete(product:kitchen.Product, time:Date)
  case class Decline(courierRef:ActorRef, product:kitchen.Product, originalSender:ActorRef)
  case object DeliverNow
  case class Unavailable(courier:ActorRef)
  case class Available(courier:ActorRef)
  def props(name:String) = Props(new Courier(name))
}


class Courier(name:String) extends Actor  with ActorLogging {
  import Courier._
  import cloudkitchens.system.dispatcher

  override val receive:Receive = available
  lazy val randomizer = new scala.util.Random(100L)

  def available:Receive = {
    case product:kitchen.Product =>
      log.info(s"$name received order to pickup product: $product")
      val pickedUpProduct = Pickup(product,new Date())
      sender() ! pickedUpProduct
      context.parent ! Unavailable(self)
      context.become(onDelivery(reminderToDeliver(),pickedUpProduct))

    case message => log.warning(s"Unrecognized message received from $sender(). The message: $message")
  }

  def reminderToDeliver():Cancellable = {
    val delay = randomizer.nextFloat() * 4 + 2 // TODO read from config
    context.system.scheduler.scheduleOnce(delay seconds) {
      self ! DeliverNow
    }
  }

  def onDelivery(scheduledAction:Cancellable, pickup: Pickup):Receive = {
    case DeliverNow =>
      val delivery = DeliveryComplete(pickup.product,new Date())
      context.parent !
      log.info(s"Delivery of product '${pickup.product} " +
        s"completed in ${((delivery.time.getTime - pickup.time.getTime).toFloat / 1000)%1.2f} seconds")
      scheduledAction.cancel()
      context.parent ! Available(self)
      sender() ! delivery
      context.become(available)
    case product:kitchen.Product =>
      log.warning(s"Courier $name received pickup order while already on delivery. Declining!")
      Decline(self, product, sender())
  }
}

object CourierDispatcher {

  case class Initialize(numberOfCouriers:Int, orderProcessorActorPath:ActorPath)
//  case class InitializeWithOrderProcessor(numberOfCouriers:Int, orderProcessorRef:ActorRef)

}

class CourierDispatcher extends Actor with Stash with ActorLogging {
  import CourierDispatcher._
  import Courier._
  val CourierDispatcher_OrderProcessor_CorrelationId = 77

  override val receive:Receive = noteReadyForService(IndexedSeq.empty)

  def noteReadyForService(slaves:IndexedSeq[ActorRefRoutee]): Receive = {

    case Initialize(n, orderProcessorPath) =>
      val slaves = for (id <- 1 to n) yield {
        val courier = context.actorOf(Courier.props(s"Courier_$id"),s"Courier_$id")
        context.watch(courier)
        ActorRefRoutee(courier)
      }
      context.actorSelection(orderProcessorPath) ! Identify (CourierDispatcher_OrderProcessor_CorrelationId)
      log.info(s"CourierDispatcher is being initialized with $n couriers in roundRobin mode with $orderProcessorPath" )
      context.become(noteReadyForService(slaves))

    case ActorIdentity(CourierDispatcher_OrderProcessor_CorrelationId, Some(orderProcessorActorRef)) =>
      context.watch(orderProcessorActorRef)
      val router = Router(RoundRobinRoutingLogic(),slaves)
      log.info(s"CourierDispatcher established connection with OrderProcessor. Ready for service!" )
      unstashAll()
      context.become(active(orderProcessorActorRef, router))

    case ActorIdentity(CourierDispatcher_OrderProcessor_CorrelationId, None) =>
      log.error(s"CourierDispatcher could not establish connection with ${CloudKitchens.OrderProcessorActorName}. Shutting down")
      context.stop(self)

    case _ =>
      log.info(s"CourierDispatcher is not active. Stashing all messages")
      stash()
  }

  def active(orderProcessor:ActorRef, router:Router):Receive = {
    case Terminated(ref) =>
      log.warning(s"Courier '${ref.path.name}' is terminated, creating replacement!")
      val newCourier = context.actorOf(
        Courier.props(s"Replacement for ${ref.path.name})"), s"${ref.path.name}_replacement")
      context.watch(newCourier)
      context.become(active(orderProcessor, router.addRoutee(ref).removeRoutee(ref)))

    case Unavailable(courierRef) =>
      log.info(s"Courier with path${courierRef.path} is now unavailable. Removed from dispatch crew")
      context.become(active(orderProcessor,router.removeRoutee(courierRef)))

    case Available(courierRef) =>
      log.info(s"Courier with path${courierRef.path} is now available. Added to dispatch crew")
      context.become(active(orderProcessor,router.addRoutee(courierRef)))

    // reroute if courier declines
    case Decline(courierRef, product,originalSender) =>
      router.route(product,originalSender)

    case message => router.route(message,sender())
  }
}


object CourierDispatcherManualTest extends App {
  import CourierDispatcher._
  val root = system.actorOf(Props[CloudKitchens])
  val dispatcher = system.actorOf(Props[CourierDispatcher],CourierDispatcherActorName)
  dispatcher ! Initialize(3,root.path/OrderProcessorActorName)
}
