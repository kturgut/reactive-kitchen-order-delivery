import akka.actor.ActorSystem


package object reactive {

  import CloudKitchens._

  val system = ActorSystem(ReactiveKitchensActorSystemName)

  val defaultKitchenActorPath = s"${ReactiveKitchensActorSystemName}/user/${CloudKitchensActorName}/Kitchen_Turkish"
  val defaultOrderProcessorActorPath = s"${ReactiveKitchensActorSystemName}/user/${OrderProcessorActorName}"

}
