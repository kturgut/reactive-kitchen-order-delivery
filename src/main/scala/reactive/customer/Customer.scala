package reactive.customer

import java.nio.file.Paths
import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.util.Timeout
import reactive.config.CustomerConfig
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.{DeliveryAcceptance, DeliveryAcceptanceRequest}
import reactive.order.OrderMonitor.OrderLifeCycleNotFoundInCache
import reactive.order.OrderProcessor.OrderReceived
import reactive.order.{Order, OrderLifeCycle, OrderOnFile}
import reactive.{CustomerActor, JacksonSerializable}
import spray.json._

import scala.concurrent.duration.DurationInt

/**
 * Customer is a stateless Actor.
 * Currently it handles these incoming messages:
 *
 * - SimulateOrdersFromFile =>
 * Reads the orders file into a stream and sends them to OrderProcessor
 * It is possible to control the rate of submissions to OrderProcessor with 'maxNumberOfOrdersPerSecond' parameter
 * shelfLifeMultiplier parameter is used to adjust the shelf life of Orders on file for testing purposes.
 *
 * - DeliveryAcceptanceRequest => DeliveryAcceptance(with tip)
 * Couriers which are assigned to delivery the PackagedProduct that is creatd for the
 * order send this message to customer at the time of delivery
 * By design, customer's tip the Couriers higher if the delivery happens within 4 seconds after order.
 *
 * TODO send DiscardOrder notice to Customer too
 */
object Customer {

  case class SimulateOrdersFromFile(orderHandler: ActorRef,
                                    maxNumberOfOrdersPerSecond: Int = 2,
                                    shelfLifeMultiplier: Float = 1,
                                    limit: Int = Int.MaxValue
                                   ) extends JacksonSerializable

}

class Customer extends Actor with ActorLogging {

  import Customer._

  val config = CustomerConfig(context.system)

  // OrderMonitor sends lastOrderReceived to Customer from persistent store, before starting simulation.
  var lastOrderOption: Option[OrderLifeCycle] = None

  override def receive: Receive = {

    case _: SystemState | ReportStatus =>
      sender ! ComponentState(CustomerActor, Operational, Some(self))

    case SimulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier, limit) =>
      simulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier, limit)

    case DeliveryAcceptanceRequest(order) =>
      sender() ! signatureWithTip(order)

    case lastOrderReceivedByOrderMonitor: OrderLifeCycle =>
      log.debug(s"Customer received info on last order received from OrderMonitor, id:${lastOrderReceivedByOrderMonitor.order.id}")
      lastOrderOption = Some(lastOrderReceivedByOrderMonitor)

    case OrderLifeCycleNotFoundInCache =>
  }

  def signatureWithTip(order: Order): DeliveryAcceptance = {
    if (Duration.between(order.createdOn, LocalDateTime.now()).toMillis < config.CustomerHappinessInMillisThreshold.toMillis)
      DeliveryAcceptance(order, "Just in time. Thank you!!", config.OnTimeDeliveryRecommendedTip)
    else
      DeliveryAcceptance(order, "Thanks", config.LateDeliveryRecommendedTip)
  }

  def simulateOrdersFromFile(orderHandler: ActorRef,
                             maxNumberOfOrdersPerSecond: Int = 2,
                             shelfLifeMultiplier: Float,
                             limit: Int): Unit = {
    implicit val timeout = Timeout(3 seconds)
    implicit val system = context.system
    val orderHandlerFlow = Flow[Order].ask[OrderReceived](4)(orderHandler)

    val sampleFlow = Flow[Order].take(limit)

    val ordersFromFileSource = FileIO.fromPath(Paths.get(config.SimulationOrderFilePath))
      .via(JsonReader.select("$[*]")).async
      .map(byteString => byteString.utf8String.parseJson.convertTo[OrderOnFile])
      .map(order => order.copy(shelfLife = (order.shelfLife * shelfLifeMultiplier).toInt))
      .map(order => order.copy(id = fixOrderId(order.id)))

    ordersFromFileSource.async
      .map(orderOnFile => Order.fromOrderOnFile(orderOnFile, self))
      .via(sampleFlow)
      .throttle(maxNumberOfOrdersPerSecond, 1.second)
      .via(orderHandlerFlow).async
      .to(Sink.ignore).run()
  }


  /**
   * Increment the IDs from file with the last id recorded in OrderMonitor persistent store
   */
  private def fixOrderId(oldId: String): String = {
    lastOrderOption match {
      case Some(lastOrder) =>
        (try {
          Some(oldId.trim.toInt + lastOrder.order.id.toInt)
        }
        catch {
          case _: NumberFormatException => None
        }) match {
          case Some(newId) => newId.toString
          case _ => oldId
        }
      case None => oldId
    }
  }

}
