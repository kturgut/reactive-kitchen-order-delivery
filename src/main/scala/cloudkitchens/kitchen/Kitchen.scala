package cloudkitchens.kitchen

import java.util.Date

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorPath, ActorRef, Identify, Props}
import cloudkitchens.CloudKitchens.{CourierDispatcherActorName, OrderProcessorActorName, OrderStreamSimulatorActorName, ShelfManagerActorName}

import cloudkitchens.order.Order


object Kitchen {
  val Kitchen_OrderProcessor_CorrelationId = 88
  val Kitchen_CourierDispatcher_CorrelationId = 99

  val TurkishCousine = "Turkish"
  val AmericanCousine = "American"

  case class KitchenReadyForService(name:String, actorRef:ActorRef)
  case class Initialize(courierDispatcherPath:ActorPath, orderProcessorPath:ActorPath)
  case class PrepareOrder(time:Date, order:Order)

  def props(name:String) = Props(new Kitchen(name))
}


class Kitchen(name:String) extends Actor with ActorLogging {
  import Kitchen._

  override val receive:Receive = closedForService(Map.empty)

  def closedForService(components: Map[String,ActorRef]): Receive = {
    case Initialize(courierDispatcherPath, orderProcessorPath) =>
      log.info(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with $courierDispatcherPath and $orderProcessorPath")
      context.actorSelection(orderProcessorPath) ! Identify (Kitchen_OrderProcessor_CorrelationId)
      context.actorSelection(courierDispatcherPath) ! Identify (Kitchen_CourierDispatcher_CorrelationId)
      context.become(closedForService(components + (ShelfManagerActorName ->context.actorOf(Props[ShelfManager],ShelfManagerActorName))))

    case ActorIdentity(Kitchen_OrderProcessor_CorrelationId, Some(orderProcessorActorRef)) =>
      log.debug(s"Kitchen is connected with ${orderProcessorActorRef.path} ")
      context.watch(orderProcessorActorRef)
      components.get(CourierDispatcherActorName) match {
        case Some(courierDispatcherRef) =>
          orderProcessorActorRef ! KitchenReadyForService(name,self)
          context.become(openForService(components(ShelfManagerActorName), orderProcessorActorRef, courierDispatcherRef))
        case None => context.become(closedForService(components + (OrderProcessorActorName ->orderProcessorActorRef)))
      }

    case ActorIdentity(Kitchen_CourierDispatcher_CorrelationId, Some(courierDispatcherActorRef)) =>
      log.debug(s"Kitchen is connected with ${courierDispatcherActorRef.path} ")
      context.watch(courierDispatcherActorRef)
      components.get(CourierDispatcherActorName) match {
        case Some(orderProcessorActorRef) =>
          orderProcessorActorRef ! KitchenReadyForService(name,self)
          context.become(openForService(components(ShelfManagerActorName), orderProcessorActorRef, courierDispatcherActorRef))
        case None => context.become(closedForService(components + (CourierDispatcherActorName ->courierDispatcherActorRef)))
      }

    case ActorIdentity(Kitchen_OrderProcessor_CorrelationId, None) =>
      log.error(s"Kitchen ${self.path} could not establish connection with ${OrderProcessorActorName}. Shutting down")
      context.stop(self)

    case ActorIdentity(Kitchen_CourierDispatcher_CorrelationId, None) =>
      log.error(s"Kitchen ${self.path} could not establish connection with ${CourierDispatcherActorName}. Shutting down")
      context.stop(self)
  }

  def openForService(shelfManager:ActorRef, orderProcessor:ActorRef, courierDispatcher:ActorRef): Receive = {
    case order: Order =>
      val product = Product(order)
      log.info(s"Kitchen $name prepared order. Sending product $product to ShelfManager to be stored until pickup by courier")
      shelfManager ! Product(order)
      courierDispatcher ! product
      orderProcessor ! product
  }
}
