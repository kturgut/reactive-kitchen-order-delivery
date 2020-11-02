package cloudkitchens.order

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.stream.alpakka.json.scaladsl.JsonReader
import spray.json._
import akka.util.Timeout
import cloudkitchens.JacksonSerializable
import cloudkitchens.order.OrderProcessor.OrderReceived

import scala.concurrent.duration.DurationInt

object OrderPipeline {

  case class SimulateOrdersFromFile(orderHandler:ActorRef, maximumNumberOfOrdersPerSecond:Int = 2)  extends JacksonSerializable
}

class OrderPipeline extends Actor with ActorLogging {
  import OrderPipeline._

  override def receive:Receive = {
    case SimulateOrdersFromFile(orderHandler, maxNumberOfOrdersPerSec) =>
      simulateOrdersFromFile(orderHandler,maxNumberOfOrdersPerSec)
  }

  def simulateOrdersFromFile(orderHandler:ActorRef, maxNumberOfOrdersPerSecond:Int = 2): Unit = {
    implicit val timeout = Timeout(3 seconds)
    implicit val materializer = ActorMaterializer()
    val orderHandlerFlow = Flow[Order].ask[OrderReceived](4)(orderHandler)

    val printFlow = Flow[Order].map{order=> println(order);order}
    val sampleFlow = Flow[Order].take(2)

    val ordersFromFileSource = FileIO.fromPath(Paths.get("./src/main/resources/orders.json"))
      .via(JsonReader.select("$[*]")).async
      .map(byteString => byteString.utf8String.parseJson.convertTo[Order])

    ordersFromFileSource.async
      .via(sampleFlow)
      .throttle(maxNumberOfOrdersPerSecond, 1.second)
    //  .via(printFlow)
      .via(orderHandlerFlow).async
      .to(Sink.ignore).run()
  }
}
