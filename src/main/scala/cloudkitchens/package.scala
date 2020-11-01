import akka.actor.{ActorSystem, Props}
import cloudkitchens.CloudKitchens.CloudKitchensActorSystemName
import cloudkitchens.delivery.CourierDispatcher
import cloudkitchens.kitchen.Kitchen
import cloudkitchens.order.OrderProcessor
import com.typesafe.config.ConfigFactory

package object cloudkitchens {

//  val system = ActorSystem(CloudKitchensSystem, ConfigFactory.load().getConfig(CloudKitchensSystem))
  val system = ActorSystem(CloudKitchensActorSystemName)


//  val orderHandlerActor = CloudKitchens.system.actorOf(Props[OrderHandler],"orderHandler")
//
//  val courierDispatcherActor = CloudKitchens.system.actorOf(Props[CourierDispatcher],"courierDispatcher")
//
//  val kitchenActor = CloudKitchens.system.actorOf(Props[Kitchen],"kitchen")

}
