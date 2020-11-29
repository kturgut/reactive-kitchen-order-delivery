package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

class KitchenConfig(config: Config) extends Extension with ConfigBase {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val SuspensionTimer: FiniteDuration = config.getDuration("suspension-timer-millis")
  val MaxNumberOfOrdersBeforeSuspension: Int = config.getInt("maximum-number-of-orders-before-suspension")

}

object KitchenConfig extends ExtensionId[KitchenConfig] with ExtensionIdProvider {

  override def lookup = KitchenConfig

  override def createExtension(system: ExtendedActorSystem) =
    new KitchenConfig(system.settings.config.getConfig("reactive.kitchen"))

  override def get(system: ActorSystem): KitchenConfig = super.get(system)
}
