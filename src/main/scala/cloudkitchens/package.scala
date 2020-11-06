import akka.actor.{ActorSystem, Props}


package object cloudkitchens {

  import CloudKitchens._

  val system = ActorSystem(CloudKitchensActorSystemName)

  val defaultKitchenActorPath = s"${CloudKitchensActorSystemName}/user/${CloudKitchensActorName}/Kitchen_Turkish"
  val defaultOrderProcessorActorPath = s"${CloudKitchensActorSystemName}/user/${OrderProcessorActorName}"

}
