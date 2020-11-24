package reactive.customer

import java.nio.file.Paths
import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.util.Timeout
import reactive.config.CustomerConfig
import reactive.{CustomerActor, JacksonSerializable, OrderMonitorActor}
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Courier.{DeliveryAcceptance, DeliveryAcceptanceRequest}
import reactive.order.OrderProcessor.OrderReceived
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
                                    maxNumberOfOrdersPerSecond: Int = 2,
                                    shelfLifeMultiplier: Float = 1,
                                    take:Int = Int.MaxValue) extends JacksonSerializable

}

class Customer extends Actor with ActorLogging {

  import Customer._
  val config = CustomerConfig(context.system)

  override def receive: Receive = {

    case _:SystemState | ReportStatus =>
      sender ! ComponentState(CustomerActor,Operational, Some(self))

    case SimulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier, takeN) =>
      simulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec, shelfLifeMultiplier, takeN)

    case DeliveryAcceptanceRequest(order) =>
      sender() ! signatureWithTip(order)
  }

  def signatureWithTip(order: Order): DeliveryAcceptance = {
    if (Duration.between(order.createdOn, LocalDateTime.now()).toMillis < config.customerHappinessInMillisThreshold.toMillis )
      DeliveryAcceptance(order, "Just in time. Thank you!!", config.onTimeDeliveryRecommendedTip)
    else
      DeliveryAcceptance(order, "Thanks", config.lateDeliveryRecommendedTip)
  }

  def simulateOrdersFromFile(orderHandler: ActorRef, maxNumberOfOrdersPerSecond: Int = 2, shelfLifeMultiplier: Float, take:Int): Unit = {
    implicit val timeout = Timeout(3 seconds)
    implicit val system = context.system
    val orderHandlerFlow = Flow[Order].ask[OrderReceived](4)(orderHandler)

    val sampleFlow = Flow[Order].take(take)

    val ordersFromFileSource = FileIO.fromPath(Paths.get(config.simulationOrderFilePath))
      .via(JsonReader.select("$[*]")).async
      .map(byteString => byteString.utf8String.parseJson.convertTo[OrderOnFile])
      .map(order => order.copy(shelfLife = (order.shelfLife * shelfLifeMultiplier).toInt))

    ordersFromFileSource.async
      .map(orderOnFile => Order.fromOrderOnFile(orderOnFile, self))
      .via(sampleFlow)
      .throttle(maxNumberOfOrdersPerSecond, 1.second)
      .via(orderHandlerFlow).async
      .to(Sink.ignore).run()
  }
}
