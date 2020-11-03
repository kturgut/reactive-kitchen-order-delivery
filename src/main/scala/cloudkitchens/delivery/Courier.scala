package cloudkitchens.delivery

import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import akka.util.Timeout
import cloudkitchens.JacksonSerializable
import cloudkitchens.kitchen.PackagedProduct
import cloudkitchens.kitchen.ShelfManager.DiscardOrder
import cloudkitchens.order.Order
import org.scalatest.time.SpanSugar.convertFloatToGrainOfTime

import scala.concurrent.duration.DurationInt
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

      val assignment1 = CourierAssignment(product.order, "BIR", courier)
      val assignment2 = CourierAssignment(product.order, "IKI", courier)
      val assignment3 = CourierAssignment(product.order, "UC", courier)

      log.info(s"$name received order to pickup product: ${product.order.name}")
      shelfManager ! assignment2
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
            case Success(message) => log.warning(s"Customer did not want to provide signature but sent this response: $message. THIS SHOULD NOT HAPPEN")
              becomeAvailable(action)
            case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
          }
        case Failure(exception) =>
          log.error(s"Exception received while picking up package. Canceling delivery! Exception detail: ${exception.getMessage}")
          becomeAvailable(action)
      }

    case product:PackagedProduct =>
      log.warning(s"Courier $name received pickup order while already on delivery. Declining!")
      context.parent ! DeclineCourierAssignment(self, product, sender())
  }

  def reminderToDeliver(courierActorRefToRemind:ActorRef):Cancellable = {
    val delay = randomizer.nextFloat() * 4 + 2 //
    log.debug (s"Wokeup with reminder to deliver. courierRef: ${courierActorRefToRemind.path}")
    context.system.scheduler.scheduleOnce(delay seconds) {
      courierActorRefToRemind ! DeliverNow
    }
  }

  private def becomeAvailable(scheduledAction:Cancellable): Unit = {
    scheduledAction.cancel()
    context.parent ! Available(self)
  }
}