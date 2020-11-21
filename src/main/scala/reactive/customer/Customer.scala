package reactive.customer

import java.nio.file.Paths
import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.util.Timeout
import reactive.JacksonSerializable
import reactive.delivery.Courier.{DeliveryAcceptance, DeliveryAcceptanceRequest}
import reactive.order.OrderLifeCycleTracker.OrderReceived
import reactive.order.{Order, OrderOnFile}
import spray.json._

import scala.concurrent.duration.DurationInt

/**
 * Customer is a stateless Actor.
 * Currently it handles these incoming messages:
 *
 * - SimulateOrdersFromFile =>
 *   Reads the orders file into a stream and sends them to OrderProcessor
 *   It is possible to control the rate of submissions to OrderProcessor with 'maxNumberOfOrdersPerSecond' parameter
 *   shelfLifeMultiplier parameter is used to adjust the shelf life of Orders on file for testing purposes.
 *
 * - DeliveryAcceptanceRequest => DeliveryAcceptance(with tip)
 *   Couriers which are assigned to delivery the PackagedProduct that is creatd for the
 *   order send this message to customer at the time of delivery
 *   By design, customer's tip the Couriers higher if the delivery happens within 4 seconds after order.
 *
 *   TODO send DiscardOrder notice to Customer too
 */
object Customer {
  val customerHappinessThresholdInMilliseconds = 4000

  case class SimulateOrdersFromFile(orderHandler: ActorRef,
                                    maxNumberOfOrdersPerSecond: Int = 2, shelfLifeMultiplier: Float = 1) extends JacksonSerializable

}

class Customer extends Actor with ActorLogging {

  import Customer._

  override def receive: Receive = {
    case SimulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier) =>
      simulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier)

    case DeliveryAcceptanceRequest(order) =>
      sender() ! signatureWithTip(order)
  }

  def signatureWithTip(order: Order): DeliveryAcceptance = {
    if (Duration.between(order.createdOn, LocalDateTime.now()).toMillis < customerHappinessThresholdInMilliseconds)
      DeliveryAcceptance(order, "Just in time. Thank you!!", 20)
    else
      DeliveryAcceptance(order, "Thanks", 3)
  }

  def simulateOrdersFromFile(orderHandler: ActorRef, maxNumberOfOrdersPerSecond: Int = 2, shelfLifeMultiplier: Float): Unit = {
    implicit val timeout = Timeout(3 seconds)
    implicit val system = context.system
    val orderHandlerFlow = Flow[Order].ask[OrderReceived](4)(orderHandler)

    val sampleFlow = Flow[Order].take(3)

    val ordersFromFileSource = FileIO.fromPath(Paths.get("./src/main/resources/orders.json"))
      .via(JsonReader.select("$[*]")).async
      .map(byteString => byteString.utf8String.parseJson.convertTo[OrderOnFile])
      .map(order => order.copy(shelfLife = (order.shelfLife * shelfLifeMultiplier).toInt))

    ordersFromFileSource.async
      .map(orderOnFile => Order.fromOrderOnFile(orderOnFile, self))
      //.via(sampleFlow)
      .throttle(maxNumberOfOrdersPerSecond, 1.second)
      .via(orderHandlerFlow).async
      .to(Sink.ignore).run()
  }
}
