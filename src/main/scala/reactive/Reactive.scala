package reactive

import akka.actor.Props
import reactive.coordinator.Coordinator

trait JacksonSerializable

object Reactive extends App {

  import reactive.coordinator.Coordinator._

  val demo = system.actorOf(Props[Coordinator], CoordinatorActor)
  demo ! Initialize
  demo ! RunSimulation(limit = 200, resetDB = false)
}


