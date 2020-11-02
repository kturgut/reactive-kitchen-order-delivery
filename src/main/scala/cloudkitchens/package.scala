import akka.actor.{ActorSystem, Props}


package object cloudkitchens {

  import CloudKitchens._

  val system = ActorSystem(CloudKitchensActorSystemName)

  val defaultKitchenActorPath = s"${CloudKitchensActorSystemName}/user/${CloudKitchensActorName}/Kitchen_Turkish"
  val defaultOrderProcessorActorPath = s"${CloudKitchensActorSystemName}/user/${OrderProcessorActorName}"


//  val orderHandlerActor = CloudKitchens.system.actorOf(Props[OrderHandler],"orderHandler")
//
//  val courierDispatcherActor = CloudKitchens.system.actorOf(Props[CourierDispatcher],"courierDispatcher")
//
//  val kitchenActor = CloudKitchens.system.actorOf(Props[Kitchen],"kitchen")

}
