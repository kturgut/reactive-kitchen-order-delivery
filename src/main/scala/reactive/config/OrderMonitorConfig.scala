package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

class OrderMonitorConfig(config: Config) extends Extension {
  val MaximumOrderLifeCycleCacheSize:Int = config.getInt("maximum-order-life-cycle-cache-size")
}

object OrderMonitorConfig extends ExtensionId[OrderMonitorConfig] with ExtensionIdProvider {

  override def lookup = OrderMonitorConfig

  override def createExtension(system: ExtendedActorSystem) =
    new OrderMonitorConfig(system.settings.config.getConfig("reactive.orderMonitor"))

  override def get(system: ActorSystem): OrderMonitorConfig = super.get(system)
}
