package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

class OrderProcessorConfig(config: Config) extends Extension {
}

object OrderProcessorConfig extends ExtensionId[OrderProcessorConfig] with ExtensionIdProvider {

  override def lookup = OrderProcessorConfig

  override def createExtension(system: ExtendedActorSystem) =
    new OrderProcessorConfig(system.settings.config.getConfig("reactive.orderProcessor"))

  override def get(system: ActorSystem): OrderProcessorConfig = super.get(system)
}
