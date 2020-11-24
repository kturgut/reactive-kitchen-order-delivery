package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

class KitchenConfig(config: Config) extends Extension with ConfigBase {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val suspensionStorageFullMillis:FiniteDuration = config.getDuration("suspension-storage-full-millis")
  val suspensionProductDiscardedMillis:FiniteDuration = config.getDuration("suspension-product-discarded-millis")
  val suspensionOverflowAboveThresholdMillis:FiniteDuration = config.getDuration("suspension-overflow-above-threshold-millis")
  val suspensionDispatcherNotAvailableMillis:FiniteDuration = config.getDuration("suspension-dispatcher-not-available-millis")
  val suspensionDispatcherAvailabilityBelowThresholdMillis:FiniteDuration = config.getDuration("suspension-dispatcher-availability-below-threshold-millis")

}

object KitchenConfig extends ExtensionId[KitchenConfig] with ExtensionIdProvider {

  override def lookup = KitchenConfig

  override def createExtension(system: ExtendedActorSystem) =
    new KitchenConfig(system.settings.config.getConfig("reactive.kitchen"))

  override def get(system: ActorSystem): KitchenConfig = super.get(system)
}
