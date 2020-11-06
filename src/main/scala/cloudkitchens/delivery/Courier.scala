package cloudkitchens.delivery

import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import akka.util.Timeout
import cloudkitchens.JacksonSerializable
import cloudkitchens.storage.ShelfManager.DiscardOrder
import cloudkitchens.order.Order
import cloudkitchens.storage.PackagedProduct
import org.scalatest.time.SpanSugar.convertFloatToGrainOfTime

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object Courier {

  val DeliveryTimeWindowSizeInSeconds = 4
  val EarliestDeliveryAfterOrderReceivedInSeconds = 2

  case class CourierAssignment(order:Order,
                               courierName:String, courierRef:ActorRef, createdOn:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString = s"Courier ${courierName} is assigned to deliver order ${order.name} with id ${order.id}."
  }

  case object DeliverNow

  case class PickupRequest(assignment:CourierAssignment, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable
  case class Pickup(product:PackagedProduct, time:LocalDateTime=LocalDateTime.now()) extends JacksonSerializable

  case class DeliveryAcceptanceRequest(order:Order) extends JacksonSerializable
  case class DeliveryAcceptance(order:Order, signature:String, tips:Int, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeclineCourierAssignment(courierRef:ActorRef, product:PackagedProduct, originalSender:ActorRef) extends JacksonSerializable

  case class DeliveryComplete(assignment:CourierAssignment, product:PackagedProduct, acceptance:DeliveryAcceptance, time:LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def durationOrderToDeliveryInSeconds: Float = Duration.between(product.createdOn, acceptance.time).toMillis.toFloat/1000
    def prettyString(): String = s"DeliveryComplete of product '${assignment.order.name} in " + f"$durationOrderToDeliveryInSeconds%1.2f" +
                                  s" seconds. Value delivered:${product.value}. Tip received:${acceptance.tips}."
  }

  case class OnAssignment(courier:ActorRef)
  case class Available(courier:ActorRef)

  def props(name:String, orderProcessor:ActorRef, shelfManager:ActorRef) = Props(new Courier(name,orderProcessor,shelfManager))
}


class Courier(name:String,orderProcessor:ActorRef, shelfManager:ActorRef) extends Actor  with ActorLogging {
  import Courier._
  import akka.pattern.ask
  import cloudkitchens.system.dispatcher

  override val receive:Receive = available
  lazy val randomizer = new scala.util.Random(100L)
  implicit val timeout = Timeout (300 milliseconds)

  def available:Receive = {
    case product:PackagedProduct =>
      val courier = self
      val assignment = CourierAssignment(product.order, name, courier)

      log.info(s"$name received order to pickup product: ${product.order.name}")
      shelfManager ! assignment
      context.parent ! OnAssignment(self)
      context.become(onDelivery(reminderToDeliver(self),assignment))


    case message =>
      log.warning(s"Unrecognized message received from $sender. The message: $message")
  }

  def onDelivery(scheduledAction:Cancellable, assignment: CourierAssignment):Receive = {

    case discard:DiscardOrder =>
      log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded due to ${discard.reason}")
      becomeAvailable(scheduledAction)

    case DeliverNow =>
      val future = shelfManager ? PickupRequest(assignment)
      val action = scheduledAction // !!? Do not use scheduleAction directly as future may be executed on different thread
      future.onComplete {
        case Success(None) =>
          log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded. Reason unknown.")
          becomeAvailable(action)
        case Success(discard:DiscardOrder) => self ! discard
        case Success(pickup:Pickup) =>
          (assignment.order.customer ? DeliveryAcceptanceRequest (pickup.product.order)).onComplete {
            case Success(acceptance:DeliveryAcceptance) =>
              val delivery = DeliveryComplete(assignment,pickup.product,acceptance)
              log.info(delivery.prettyString)
              orderProcessor ! delivery
              becomeAvailable(action)
            case Success(message) => log.error(s"Customer did not want to provide signature but sent this response: $message. THIS SHOULD NOT HAPPEN")
              becomeAvailable(action)
            case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
          }
        case Failure(exception) =>
          log.error(s"Exception received while picking up package. Canceling delivery! Exception detail: ${exception.getMessage}")
          becomeAvailable(action)
        case other => log.error(s"Unexpected response received $other")
      }

    case product:PackagedProduct =>
      log.warning(s"Courier $name received pickup order while already on delivery. Declining!")
      context.parent ! DeclineCourierAssignment(self, product, sender())
  }

  def reminderToDeliver(courierActorRefToRemind:ActorRef):Cancellable = {
    val delay = randomizer.nextFloat() * DeliveryTimeWindowSizeInSeconds + EarliestDeliveryAfterOrderReceivedInSeconds
    context.system.scheduler.scheduleOnce(delay seconds) {
      courierActorRefToRemind ! DeliverNow
    }
  }

  private def becomeAvailable(scheduledAction:Cancellable): Unit = {
    scheduledAction.cancel()
    context.parent ! Available(self)
    context.become(available)
  }
}