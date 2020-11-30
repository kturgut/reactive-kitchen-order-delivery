import akka.actor.ActorSystem


package object reactive {

  val ActorSystemName = "Reactive"
  val KitchenActor = "Kitchen"
  val ShelfManagerActor = "ShelfManager"
  val OrderProcessorActor = "OrderProcessor"
  val OrderMonitorActor = "OrderMonitor"
  val DispatcherActor = "Dispatcher"
  val CustomerActor = "Customer"
  val CoordinatorActor = "Coordinator"

  val InitializationTimeInMillis = 300

  val system = ActorSystem(ActorSystemName)

  val defaultKitchenActorPath = s"${ActorSystemName}/user/${CoordinatorActor}/Kitchen_Turkish"
  val defaultOrderProcessorActorPath = s"${ActorSystemName}/user/${OrderProcessorActor}"

}
