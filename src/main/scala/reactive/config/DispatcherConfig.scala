package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

class DispatcherConfig(config: Config) extends Extension {

  val MinimumAvailableToRecruitedCouriersRatio = config.getDouble("minimum-available-to-recruited-couriers-ratio").toFloat
  val NumberOfCouriersToRecruitInBatches = config.getInt("number-of-couriers-to-recruit-in-batches")
  val MaximumNumberOfCouriers = config.getInt("max-number-of-couriers")

}


object DispatcherConfig extends ExtensionId[DispatcherConfig] with ExtensionIdProvider {

  override def lookup = DispatcherConfig

  override def createExtension(system: ExtendedActorSystem) =
    new DispatcherConfig(system.settings.config.getConfig("reactive.dispatcher"))

  override def get(system: ActorSystem): DispatcherConfig = super.get(system)
}





