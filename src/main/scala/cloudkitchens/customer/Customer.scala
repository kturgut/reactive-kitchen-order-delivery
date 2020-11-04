package cloudkitchens.customer

import java.nio.file.Paths
import java.time.{Duration, LocalDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.util.Timeout
import cloudkitchens.JacksonSerializable
import cloudkitchens.delivery.Courier.{DeliveryAcceptance, DeliveryAcceptanceRequest}
import cloudkitchens.order.{Order, OrderOnFile}
import cloudkitchens.order.OrderProcessor.OrderReceived
import spray.json._

import scala.concurrent.duration.DurationInt

object Customer {
  val customerHappinessThresholdInMilliseconds = 4000

  case class SimulateOrdersFromFile(orderHandler:ActorRef, maximumNumberOfOrdersPerSecond:Int = 2, realistic:Boolean=true)  extends JacksonSerializable
}

class Customer extends Actor with ActorLogging {
  import Customer._

  override def receive:Receive = {
    case SimulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec,realistic) =>
      simulateOrdersFromFile(orderHandler,maxNumberOfOrdersPerSec,realistic)

    case DeliveryAcceptanceRequest(order) =>
      sender() ! signatureWithTip(order)
  }

  def signatureWithTip(order: Order):DeliveryAcceptance = {
    if (Duration.between(order.createdOn, LocalDateTime.now()).toMillis < customerHappinessThresholdInMilliseconds)
      DeliveryAcceptance(order, "Just in time. Thank you!!", 20)
    else
      DeliveryAcceptance(order, "Thanks", 3)
  }

  def simulateOrdersFromFile(orderHandler:ActorRef, maxNumberOfOrdersPerSecond:Int = 2, realistic:Boolean): Unit = {
    implicit val timeout = Timeout(3 seconds)
    implicit val system = context.system
    val orderHandlerFlow = Flow[Order].ask[OrderReceived](4)(orderHandler)

    //val printFlow = Flow[Order].map{order=> println(order);order}
    val sampleFlow = Flow[Order].take(1)

    val ordersFromFileSource = FileIO.fromPath(Paths.get("./src/main/resources/orders.json"))
      .via(JsonReader.select("$[*]")).async
      .map(byteString => byteString.utf8String.parseJson.convertTo[OrderOnFile])
      //.map(order=> if (realistic) order.copy(shelfLife = order.shelfLife / 10) else order)
      .map(order=> if (realistic) order.copy(shelfLife = order.shelfLife * 10) else order)

    ordersFromFileSource.async
      .map(orderOnFile=> Order.fromOrderOnFile(orderOnFile,self))
      .via(sampleFlow)
      .throttle(maxNumberOfOrdersPerSecond, 1.second)
    //  .via(printFlow)
      .via(orderHandlerFlow).async
      .to(Sink.ignore).run()
  }
}
