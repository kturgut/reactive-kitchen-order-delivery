package reactive.kitchen

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Stash, Timers}
import akka.util.Timeout
import javafx.util.Duration
import reactive.ReactiveKitchens.ShelfManagerActorName
import reactive.JacksonSerializable
import reactive.order.Order
import reactive.storage.ShelfManager.{CapacityUtilization, DiscardOrder}
import reactive.storage.{PackagedProduct, ShelfManager}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Kitchen is a Stateless Actor. Parent Actor is CloudKitchens.
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
  val DelayInMillisIfStorageIsFull = 1000
  val DelayInMillisIfProductIsDiscarded = 1500
  val DelayInMillisIfOverflowIsAboveThreshold = 1000
  val OverflowUtilizationSafetyThreshold = 0.9

  def props(name: String, expectedOrdersPerSecond: Int) = Props(new Kitchen(name, expectedOrdersPerSecond))

  case class InitializeKitchen(orderProcessor: ActorRef,
                               courierDispatcher: ActorRef) extends JacksonSerializable

  case class KitchenReadyForService(name: String, expectedOrdersPerSecond: Int, kitchenRef: ActorRef,
                                    orderProcessorRef: ActorRef, shelfManagerRef: ActorRef) extends JacksonSerializable

  case object ResumeTakingOrders

}


class Kitchen(name: String, expectedOrdersPerSecond: Int) extends Actor with ActorLogging with Timers with Stash {

  import Kitchen._
  import reactive.system.dispatcher
  import akka.pattern.ask
  implicit val timeout = Timeout(300 milliseconds)

  override val receive: Receive = closedForService

  def closedForService: Receive = {
    case InitializeKitchen(orderProcessor, courierDispatcher) =>
      log.debug(s"Initializing Kitchen ${self.path.toStringWithoutAddress} with $courierDispatcher and $orderProcessor")
      val shelfManager = context.actorOf(ShelfManager.props(Some(self),Some(orderProcessor)), ShelfManagerActorName)
      context.watch(shelfManager)
      val readyNotice = KitchenReadyForService(name, expectedOrdersPerSecond, self, orderProcessor, shelfManager)
      orderProcessor ! readyNotice
      courierDispatcher ! readyNotice
      context.become(openForService(shelfManager, orderProcessor, courierDispatcher))
  }

  def openForService(shelfManager: ActorRef, orderProcessor: ActorRef, courierDispatcher: ActorRef): Receive = {
    case order: Order =>
      val product = PackagedProduct(order)
      log.info(s"Kitchen $name prepared order for '${order.name}' id:${order.id}. Sending it to ShelfManager for courier pickup")
      shelfManager ! PackagedProduct(order)
      courierDispatcher ! product
      orderProcessor ! product

    case _: DiscardOrder =>
      context.become(suspendIncomingOrders(resume(self, DelayInMillisIfProductIsDiscarded),shelfManager,orderProcessor,courierDispatcher))

    case CapacityUtilization(overflowUtilization,_) => checkShelfCapacity(overflowUtilization, shelfManager,orderProcessor,courierDispatcher)
  }


  /**
   * If overflow capacity utilization exceeds OverflowUtilizationSafetyThreshold is received,
   * Kitchen temporarily suspends processing incoming orders for DelayInMillisIfOverflowIsAboveThreshold milliseconds.
   *
   * If DiscardOrder is received, Kitchen temporarily suspends processing incoming orders for
   * DelayInMillisIfProductIsDiscarded milliseconds.
   *
   * Before Kitchen resumes processing it confirms that Shelf overflow capacity utilization is below threshold.
   *
   * @param schedule
   * @param shelfManager
   * @param orderProcessor
   * @param courierDispatcher
   * @return
   */
  def suspendIncomingOrders(schedule:Cancellable, shelfManager: ActorRef, orderProcessor: ActorRef, courierDispatcher: ActorRef):Receive = {
    case order:Order =>
      log.debug(s"Kitchen stashing message received in suspended state: $order")
      stash()

    case _:DiscardOrder =>
      schedule.cancel()
      context.become(suspendIncomingOrders(resume(self, DelayInMillisIfProductIsDiscarded),shelfManager,orderProcessor,courierDispatcher))

    case ResumeTakingOrders =>
      schedule.cancel()
      (shelfManager ? ShelfManager.RequestCapacityUtilization).onComplete {
        case Success(CapacityUtilization(overflowUtilization,_)) =>
          if (overflowUtilization > OverflowUtilizationSafetyThreshold) {
            log.debug(s"Shelf overflow capacity utilization exceeds max threshold: $overflowUtilization. Will temporarily pause processing orders.")
            context.become(suspendIncomingOrders(resume(self, DelayInMillisIfOverflowIsAboveThreshold),shelfManager,orderProcessor,courierDispatcher))
          }
          else {
            log.debug(s"Shelf overflow capacity utilization is below max threshold: $overflowUtilization. Will resume taking orders.")
            unstashAll()
            context.become(openForService(shelfManager, orderProcessor, courierDispatcher))
          }
        case Failure(exception) => log.error(s"Exception received while waiting for customer signature. ${exception.getMessage}")
      }

    case CapacityUtilization(overflowUtilization,_) => checkShelfCapacity(overflowUtilization, shelfManager,orderProcessor,courierDispatcher, Some(schedule))

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  def resume(kitchen: ActorRef, pauseInMillis:Int): Cancellable = {
    context.system.scheduler.scheduleOnce(pauseInMillis millis) {
      kitchen ! ResumeTakingOrders
    }
  }

  def checkShelfCapacity(overflowUtilization:Float, shelfManager: ActorRef, orderProcessor: ActorRef, courierDispatcher: ActorRef, prevSchedule:Option[Cancellable]=None):Unit = {
    prevSchedule.foreach(_.cancel())
    if (overflowUtilization > OverflowUtilizationSafetyThreshold) {
      log.debug(s"Shelf overflow capacity utilization exceeds max threshold: $overflowUtilization. Will temporarily pause processing orders.")
      context.become(suspendIncomingOrders(resume(self, DelayInMillisIfOverflowIsAboveThreshold), shelfManager, orderProcessor, courierDispatcher))
    }
  }


}
