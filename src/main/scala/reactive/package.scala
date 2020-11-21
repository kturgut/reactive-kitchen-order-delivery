import akka.actor.ActorSystem


package object reactive {

  import ReactiveKitchens._

  val system = ActorSystem(ReactiveKitchensActorSystemName)

  val defaultKitchenActorPath = s"${ReactiveKitchensActorSystemName}/user/${ReactiveKitchensActorName}/Kitchen_Turkish"
  val defaultOrderProcessorActorPath = s"${ReactiveKitchensActorSystemName}/user/${OrderProcessorActorName}"

}
