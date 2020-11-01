package akka

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import cloudkitchens.order.Order
import cloudkitchens.order.OrderProcessor.OrderReceived

import scala.concurrent.duration.DurationInt

object IntegratingWithActors extends App {
  implicit val system = ActorSystem("TestStreams")
  implicit val materializer = ActorMaterializer()

  class SimpleActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case s:String=>
          log.info(s"Just received a string: $s")
          sender() ! s"$s$s"
      case n:Int =>
          log.info(s"Just received a number: $n")
          sender() ! 2 * n
      case _ =>
    }
  }

  val simpleActor = system.actorOf(Props[SimpleActor], "simpleActor")

  val numbersSource = Source (1 to 10)

  implicit val timeout = Timeout(2 seconds)

  // ACTOR as a flow
  val actorBasedFlow = Flow[Int].ask[Int](parallelism = 2) (simpleActor)

  // numbersSource.via(actorBasedFlow).to(Sink.foreach[Int](println)).run()
  // numbersSource.via(actorBasedFlow).to(Sink.ignore).run()
  numbersSource.ask[Int](simpleActor).to(Sink.ignore).run()

  // Actor as a source
  val actorPoweredSource = Source.actorRef[Int](bufferSize = 10, overflowStrategy = OverflowStrategy.dropHead)
  val materializedActorRef = actorPoweredSource.to(Sink.foreach[Int](number => println(s"Actor powered flow got $number"))).run()
  materializedActorRef ! 10
  // terminate the stream
  materializedActorRef ! akka.actor.Status.Success("complete")


  /* Actor as a destination/sink
  - an init message
  - an ack message to confirm reception
  - a complete message
  - a function to generate a message in case the stream throws exception
   */

  case object StreamInit
  case object StreamAck
  case object StreamComplete
  case class StreamFail(ex:Throwable)

  class DestinationActor extends Actor with ActorLogging {
    override def receive:Receive = {
      case StreamInit =>
          log.info("Stream initialized")
    //    sender() ! StreamAck
      case StreamComplete =>
        log.info("Stream complete")
    //    context.stop(self)
      case StreamFail(ex) =>
        log.warning(s"Stream failed $ex")
      case number:Int =>
        log.info(s"Message  $number has come to its final destination")
        sender ! number + 1000
      case order:Order =>
        log.info(s"Order received $order")
        sender ! OrderReceived("asdf")
      case message =>
          log.info(s"Message  $message has come to its final destination")
         // sender() ! "Thanks"
         // sender() ! StreamAck // have to respond otherwise will be treated as backpressure
    }
  }
//  val destinationActor = system.actorOf(Props[DestinationActor], "destinationActor")
//
//  val actorPoweredSink = Sink.actorRefWithAck[Int] (
//    destinationActor,
//    onInitMessage = StreamInit,
//    onCompleteMessage = StreamComplete,
//    ackMessage = StreamAck,
//    onFailureMessage = throwable => StreamFail(throwable) // optional
//  )
//  Source (1 to 10). to(actorPoweredSink).run()
//
}
