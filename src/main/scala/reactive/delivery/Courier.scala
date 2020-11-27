package reactive.delivery

import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import akka.util.Timeout
import org.scalatest.time.SpanSugar.convertFloatToGrainOfTime
import reactive.JacksonSerializable
import reactive.config.{CourierConfig, DispatcherConfig}
import reactive.order.Order
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Courier is a Stateless Actor. Parent Actor is CourierDispatcher.
 * Couriers deliver the PackagedProducts to Customers.
 * Couriers are created by CourierDispatcher as needed.
 * Couriers are
 *
 * Courier can be in one of two states at any one time
 * 1- available
 * 2- onDelivery
 *
 * Timers: OneTime timer is created when courier goes from available to onDelivery.
 *
 * Couriers handle the following incoming messages
 * when available:
 * PackagedProduct =>
 * CourierAssignment to ShelfManager
 * OnAssignment to CourierDispatcher
 * Timer: Start a one time timer to send a 'DeliverNow' message to self within 2-6 seconds
 * become available.
 * when onDelivery:
 * DeliverNow =>
 * Send a PickupRequest to ShelfManager. (Expect to receive with a timeout). Response could be:
 * Pickup: This acknowledges that the product is ready to be picked up at ShelfManager
 * Courier sends a DeliveryAcceptanceRequest to Customer.
 * Customer currently only respond with DeliveryAcceptance with a tip amount. If this is received,
 * customer notifies the Order processor that Order is complete and dispatcher that he is available.
 * If no response received within timeout limit, Courier drops the order and notifies dispatcher.
 * DiscardOrder: which the Courier sends to itself
 * DiscardOrder => Send Available to CourierDispatcher, and become available.
 * PackagedProduct => If for any reason it receives a PackagedProduct while on Delivery,
 * Courier sends a DeclineAssignment to Dispatcher.
 */

object Courier {

  def props(name: String, orderMonitor: ActorRef, shelfManager: ActorRef) = Props(new Courier(name, orderMonitor, shelfManager))

  case class CourierAssignment(order: Order,
                               courierName: String, courierRef: ActorRef, createdOn: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString = s"Courier ${courierName} is assigned to deliver order ${order.name} with id ${order.id}."
  }

  case class PickupRequest(assignment: CourierAssignment, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class Pickup(product: PackagedProduct, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeliveryAcceptanceRequest(order: Order) extends JacksonSerializable

  case class DeliveryAcceptance(order: Order, signature: String, tips: Int, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeclineCourierAssignment(courierRef: ActorRef, product: PackagedProduct, originalSender: ActorRef) extends JacksonSerializable

  case class DeliveryComplete(assignment: CourierAssignment, product: PackagedProduct, acceptance: DeliveryAcceptance, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString(): String = s"DeliveryComplete of product '${assignment.order.name} in " + f"$durationOrderToDeliveryInSeconds%1.2f" +
      s" seconds. Value delivered:${product.value}. Tip received:${acceptance.tips}. Order id:${assignment.order.id}"

    def durationOrderToDeliveryInSeconds: Float = Duration.between(product.createdOn, acceptance.time).toMillis.toFloat / 1000
  }

//  case class OnAssignment(courier: ActorRef)

  case class Available(courier: ActorRef)

  case object DeliverNow

}


class Courier(name: String, orderMonitor: ActorRef, shelfManager: ActorRef) extends Actor with ActorLogging {

  val config = CourierConfig(context.system)

  import Courier._
  import akka.pattern.ask
  import reactive.system.dispatcher

  lazy val randomizer = new scala.util.Random(100L)
  override val receive: Receive = available
  implicit val timeout = config.timeout

  def available: Receive = {
    case product: PackagedProduct =>
      val courier = self
      val assignment = CourierAssignment(product.order, name, courier)
      log.info(s"$name received order to pickup product: ${product.order.name} id:${product.order.id}")
      context.parent ! assignment
      if (sender()!=context.parent) {
        sender ! assignment
      }
      context.become(onDelivery(reminderToDeliver(self), assignment))

    case message =>
      log.warning(s"Unrecognized message received from $sender. The message: $message")
  }

  def onDelivery(scheduledAction: Cancellable, assignment: CourierAssignment): Receive = {

    case discard: DiscardOrder =>
      log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded due to ${discard.reason}")
      becomeAvailable(scheduledAction)

    case DeliverNow =>
      val future = shelfManager ? PickupRequest(assignment)
      val action = scheduledAction // !!? Do not use scheduleAction directly as future may be executed on different thread
      future.onComplete {
        case Success(None) =>
          log.info(s"Cancelling trip for delivery for ${assignment.order.name} as product was discarded. Reason unknown.")
          becomeAvailable(action)
        case Success(discard: DiscardOrder) => self ! discard
        case Success(pickup: Pickup) =>
          (assignment.order.customer ? DeliveryAcceptanceRequest(pickup.product.order)).onComplete {
            case Success(acceptance: DeliveryAcceptance) =>
              val delivery = DeliveryComplete(assignment, pickup.product, acceptance)
              log.info(delivery.prettyString)
              orderMonitor ! delivery
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

    case product: PackagedProduct =>
      log.warning(s"Courier $name received pickup order with id:${product.order.id} while already on delivery. Declining!")
      context.parent ! DeclineCourierAssignment(self, product, sender())
  }

  def reminderToDeliver(courierActorRefToRemind: ActorRef): Cancellable = {
    val delay = randomizer.nextFloat() * config.DeliveryTimeWindowMillis + config.EarliestDeliveryAfterOrderReceivedMillis
    context.system.scheduler.scheduleOnce(delay millis) {
      courierActorRefToRemind ! DeliverNow
    }
  }

  private def becomeAvailable(scheduledAction: Cancellable): Unit = {
    scheduledAction.cancel()
    context.parent ! Available(self)
    context.become(available)
  }
}