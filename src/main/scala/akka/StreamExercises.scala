package akka

import akka.IntegratingWithActors.{StreamComplete, StreamFail, StreamInit}
import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.stream.{ActorMaterializer, DelayOverflowStrategy, KillSwitches}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object StreamExercises extends App {

  implicit val system = ActorSystem("Test")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  def asyncBoundary () = {

    val simpleSource = Source(1 to 10)
    val simpleFlow = Flow[Int].map(_ + 1)
    val simpleFlow2 = Flow[Int].map(_ * 10)
    val simpleSink = Sink.foreach[Int](println)

    val complexFLow = Flow[Int].map {x => Thread.sleep(1000); x + 1}
    val complexFLow2 = Flow[Int].map {x => Thread.sleep(1000); x * 10}

    // runs on the same actor with operator fusion
    simpleSource.via(simpleFlow).via(simpleFlow2).to(simpleSink).run()
    // async boundary
    simpleSource.via(complexFLow).async
      .via(complexFLow2).async
      .to (simpleSink)
      .run()
  }

  def futures(): Unit = {
    val source = Source (1 to 10)
    val sink = Sink.reduce[Int]((a,b) => a+ b)
    val sumFuture = source.runWith(sink)
    sumFuture.onComplete {
      case Success(value) => println (s"Sum is $value")
      case Failure(exception) => println(s"Exception happened :( ${exception}")
    }
  }
  //futures()

  def runWith() = {
    val sum = Source[Int](1 to 10).runWith(Sink.reduce[Int](_+_))
    println(s"Sum is calculated with runWith: $sum")
  }
  //runWith()



  def foldWordCount() = {
    val sentenceSource = Source(List("Akka is awesome", "I love streams", "Materialized values are nice"))
    val wordCountSink = Sink.fold[Int,String](0)((currentWords,newSentence) => currentWords + newSentence.split(" ").length )
    val g1 = sentenceSource.toMat(wordCountSink)(Keep.right).run()
    val wordCountFlow = Flow[String].fold[Int](0)((currentWords,newSentence) => currentWords + newSentence.split(" ").length )
    val g4 = sentenceSource.via(wordCountFlow).toMat(Sink.head)(Keep.right).run() // keeps first word
    val g5 = sentenceSource.viaMat(wordCountFlow)(Keep.left).toMat(Sink.head)(Keep.right).run() // keeps first word
    val g6 = sentenceSource.via(wordCountFlow).runWith(Sink.head)
    val g7 = wordCountFlow.runWith(sentenceSource,Sink.head)._2
  }

  foldWordCount()

}
