package reactive.delivery

import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import org.scalatest.time.SpanSugar.convertFloatToGrainOfTime
import reactive.JacksonSerializable
import reactive.config.CourierConfig
import reactive.order.Order
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder

import scala.util.{Failure, Success}
// @formatter:off
/**
 * Courier is a Stateless Actor. Parent Actor is Dispatcher.
 * Courier received a PackagedProduct as indication of a delivery request from Kitchen.
 * Courier responds with CourierAssignment or DeclineCourierAssignment in response
 * Once on CourierAssignment, it will communicate with ShelfManager requesting Pickup and then with Customer to request
 * acceptance of delivery. All these changes in order life cycle are communicated to OrderMonitor for durable persistence.
 *
 * Courier can be in one of two states at any one time
 *   1- available
 *   2- onDelivery
 *
 * Timers
 *   reminderToDeliver: This timer kicks in at random time within the delivery window. Delivery window can be configured from the config
 *         For example: between 2-6 seconds after order is received.
 *
 * Courier handles the following incoming messages
 *   when available:
 *      PackagedProduct =>
 *          Courier responds with CourierAssignment to sender as well as its parent Dispatcher.
 *          It sets a timer to remember to make the delivery within delivery window.
 *          DeliveryWindow is controlled by config.
 *   when active:
 *       DeliverNow =>
 *          Courier sends PickupRequest to ShelfManager expecting 'Pickup' as response. If it receives 'Pickup' it then
 *          sends a DeliveryAcceptanceRequest to customer. If it receives DeliveryAcceptance from customer then this is a
 *          successful delivery. OrderMonitor is notified with DeliveryComplete. Courier then notifies Dispatcher that it is
 *          available for subsequent delivery
 *
 *          It is possible that PickupRequest gets a DiscardOrder response from ShelfManager. In this case Courier becomes
 *          available for subsequent delivery.
 */
// @formatter:on

object Courier {

  def props(name: String, orderMonitor: ActorRef, shelfManager: ActorRef) = Props(new Courier(name, orderMonitor, shelfManager))

  case class CourierAssignment(order: Order, courierName: String, courierRef: ActorRef,
                               createdOn: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString = s"Courier $courierName is assigned to deliver order ${order.name} with id:${order.id}."
  }

  case class PickupRequest(assignment: CourierAssignment, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class Pickup(product: PackagedProduct, time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeliveryAcceptanceRequest(order: Order) extends JacksonSerializable

  case class DeliveryAcceptance(order: Order, signature: String, tips: Int,
                                time: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable

  case class DeclineCourierAssignment(name: String, courierRef: ActorRef,
                                      product: PackagedProduct, originalSender: ActorRef) extends JacksonSerializable

  case class DeliveryComplete(assignment: CourierAssignment, product: PackagedProduct,
                              acceptance: DeliveryAcceptance, createdOn: LocalDateTime = LocalDateTime.now()) extends JacksonSerializable {
    def prettyString(): String = s"DeliveryComplete of product '${assignment.order.name} in " + f"$durationOrderToDeliveryInSeconds%1.2f" +
      s" seconds. Value delivered:${product.value}. Tip received:${acceptance.tips}. Order id:${assignment.order.id}"

    def durationOrderToDeliveryInSeconds: Float = Duration.between(product.createdOn, acceptance.time).toMillis.toFloat / 1000
  }

  case class Available(courier: ActorRef)

  case object DeliverNow

}


class Courier(name: String, orderMonitor: ActorRef, shelfManager: ActorRef) extends Actor with ActorLogging {
  lazy val randomizer = new scala.util.Random(100L)

  import Courier._
  import akka.pattern.ask
  import reactive.system.dispatcher

  override val receive: Receive = available
  val config = CourierConfig(context.system)
  implicit val timeout = config.timeout

  def available: Receive = {
    case product: PackagedProduct =>
      val courier = self
      val assignment = CourierAssignment(product.order, name, courier)
      log.info(s"$name received order to pickup product: ${product.order.name} id:${product.order.id}")
      if (sender() != context.parent) {
        sender ! assignment
      }
      context.parent ! assignment
      context.become(onDelivery(reminderToDeliver(self), assignment))

    case message =>
      log.warning(s"Unrecognized message received from $sender. The message: $message")
  }

  def onDelivery(scheduledAction: Cancellable, assignment: CourierAssignment): Receive = {

    case discard: DiscardOrder =>
      log.info(s"$name cancelling delivery for discarded order with id:${assignment.order.id}. Reason:${discard.reason}")
      becomeAvailable(scheduledAction)

    case DeliverNow =>
      val future = shelfManager ? PickupRequest(assignment)
      val action = scheduledAction
      future.onComplete {
        case Success(None) =>
          log.info(s"Cancelling delivery for order with id:${assignment.order.id} as product could not be located on shelf. Reason unknown.")
          becomeAvailable(action)
        case Success(discard: DiscardOrder) => self ! discard
        case Success(pickup: Pickup) =>
          (assignment.order.customer ? DeliveryAcceptanceRequest(pickup.product.order)).onComplete {
            case Success(acceptance: DeliveryAcceptance) =>
              val delivery = DeliveryComplete(assignment, pickup.product, acceptance)
              log.info(delivery.prettyString)
              orderMonitor ! delivery
              becomeAvailable(action)
            case Success(message) => log.warning(s"Customer did not want to provide signature but sent this response: $message.")
              becomeAvailable(action)
            case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
          }
        case Failure(exception) =>
          log.error(s"Exception received while picking up package. Canceling delivery! Exception detail: ${exception.getMessage}")
          becomeAvailable(action)
        case other => log.error(s"Unexpected response received $other")
      }

    case product: PackagedProduct =>
      log.warning(s"Courier $name received pickup order with id:${product.order.id} " +
        s"while already on delivery for order with id:${assignment.order.id}. Declining!")
      context.parent ! DeclineCourierAssignment(name, self, product, sender())
  }

  def reminderToDeliver(courierActorRefToRemind: ActorRef): Cancellable = {
    val delay = randomizer.nextFloat() * config.DeliveryTimeWindowMillis + config.EarliestDeliveryAfterOrderReceivedMillis
    log.debug(s"Courier $name's  expected time of delivery for in ${delay millis} millis")
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