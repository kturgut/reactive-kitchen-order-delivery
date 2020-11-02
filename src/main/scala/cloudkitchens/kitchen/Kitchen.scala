package cloudkitchens.kitchen

import java.util.Date

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorPath, ActorRef, Identify, Props, Timers}
import cloudkitchens.CloudKitchens.ShelfManagerActorName
import cloudkitchens.{ComponentStatus, JacksonSerializable, NotReadyForService, ReadyForService}
import cloudkitchens.order.Order


object Kitchen {
  val Kitchen_OrderProcessor_CorrelationId = 88
  val Kitchen_CourierDispatcher_CorrelationId = 99

  val TurkishCousine = "Turkish"
  val AmericanCousine = "American"

  case class InitializeKitchen(orderProcessor:ActorRef,
                               courierDispatcher:ActorRef)  extends JacksonSerializable
  case class KitchenReadyForService(name:String, expectedOrdersPerSecond:Int, kitchenRef:ActorRef,
                               orderProcessorRef:ActorRef, shelfManagerRef:ActorRef) extends JacksonSerializable

  case class PrepareOrder(time:Date, order:Order)  extends JacksonSerializable

  def props(name:String, expectedOrdersPerSecond:Int) = Props(new Kitchen(name, expectedOrdersPerSecond))
}


class Kitchen(name:String, expectedOrdersPerSecond:Int) extends Actor with ActorLogging with Timers {
  import Kitchen._

  override val receive:Receive = closedForService

  def closedForService: Receive = {
    case InitializeKitchen(orderProcessor,courierDispatcher) =>
      log.info(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with $courierDispatcher and $orderProcessor")
      val shelfManager = context.actorOf(ShelfManager.props(courierDispatcher,Some(orderProcessor)),ShelfManagerActorName)
      val readyNotice = KitchenReadyForService(name,expectedOrdersPerSecond, self,orderProcessor,shelfManager)
      orderProcessor ! readyNotice
      courierDispatcher ! readyNotice
      context.become(openForService(shelfManager, orderProcessor, courierDispatcher))
  }

  def openForService(shelfManager:ActorRef, orderProcessor:ActorRef, courierDispatcher:ActorRef): Receive = {
    case order: Order =>
      val product = PackagedProduct(order)
      log.info(s"Kitchen $name prepared order. Sending product $product to ShelfManager to be stored until pickup by courier")
      shelfManager ! PackagedProduct(order)
      courierDispatcher ! product
      orderProcessor ! product
  }
}
