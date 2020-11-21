package reactive.kitchen

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import reactive.CloudKitchens.ShelfManagerActorName
import reactive.JacksonSerializable
import reactive.order.Order
import reactive.storage.{PackagedProduct, ShelfManager}

/** Kitchen is a Stateless Actor. Parent Actor is CloudKitchens.
  *   Kitchen receives Orders from OrderProcessor and prepares PackagedProduct.
  *   PackagedProducts are sent to ShelfManager for storage.
  *
  * CourierDispatcher can be in one of two states at any one time
  *   1- closedForService
  *   2- openForService
  *
  * Kitchen handles the following incoming messages
  *   when closedForService:
  *      InitializeKitchen => This helps connect Kitchen with OrderProcessor and CourierDispatcher
  *           It creates its child actor ShelfManager and sends KitchenReadyForService message to it to connect it
  *           with these other actors
  *    when openForService:
  *      Order => Prepare the order and then create a PackagedProduct
  *           Creation of PackagedProduct is tracked as an OrderLifeCycle event, so send it to OrderProcessor.
  *           Also send the PackagedProduct to ShelfManager for storage.
  *           Also notify CourierDispatcher that a new PackagedProduct is created and it should allocate a Courier for delivery
  *
  *  TODO:
  *  - Throughput: Currently throttling is done at Customer -> OrderProcessor. We can implement a custom BackPressure
  *    Logic between OrderProcessor and Kitchen. One way of doing this is observing the capacity utilization in Storage
  *    by interacting with Shelf Manager and stashing messages until Capacity utilization falls to a certain threshold
  *
  *  - CircuitBreaker: If for any reason either the CourierDispatcher or ShelfManager becomes unresponsive or overloaded,
  *    we can put a CircuitBreaker in between and stash the incoming messages until they get stabilized.
  */

object Kitchen {
  val TurkishCousine = "Turkish"
  val AmericanCousine = "American"

  def props(name: String, expectedOrdersPerSecond: Int) = Props(
    new Kitchen(name, expectedOrdersPerSecond)
  )

  case class InitializeKitchen(
    orderProcessor: ActorRef,
    courierDispatcher: ActorRef
  ) extends JacksonSerializable

  case class KitchenReadyForService(
    name: String,
    expectedOrdersPerSecond: Int,
    kitchenRef: ActorRef,
    orderProcessorRef: ActorRef,
    shelfManagerRef: ActorRef
  ) extends JacksonSerializable
}

class Kitchen(name: String, expectedOrdersPerSecond: Int)
    extends Actor
    with ActorLogging
    with Timers {

  import Kitchen._

  override val receive: Receive = closedForService

  def closedForService: Receive = {
    case InitializeKitchen(orderProcessor, courierDispatcher) =>
      log.debug(
        s"Initializing Kitchen ${self.path.toStringWithoutAddress} with $courierDispatcher and $orderProcessor"
      )
      val shelfManager = context.actorOf(
        ShelfManager.props(Some(orderProcessor)),
        ShelfManagerActorName
      )
      context.watch(shelfManager)
      val readyNotice = KitchenReadyForService(
        name,
        expectedOrdersPerSecond,
        self,
        orderProcessor,
        shelfManager
      )
      orderProcessor ! readyNotice
      courierDispatcher ! readyNotice
      context.become(
        openForService(shelfManager, orderProcessor, courierDispatcher)
      )
  }

  def openForService(
    shelfManager: ActorRef,
    orderProcessor: ActorRef,
    courierDispatcher: ActorRef
  ): Receive = { case order: Order =>
    val product = PackagedProduct(order)
    log.info(
      s"Kitchen $name prepared order for ${order.name}. Sending it to ShelfManager for courier pickup"
    )
    shelfManager ! PackagedProduct(order)
    courierDispatcher ! product
    orderProcessor ! product
  }
}
