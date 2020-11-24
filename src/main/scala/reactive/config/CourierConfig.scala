package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

class CourierConfig(config: Config) extends Extension with ConfigBase {
  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val DeliveryTimeWindowMillis: Long = config.getLong("delivery-time-window-millis")
  val EarliestDeliveryAfterOrderReceivedMillis: Long = config.getLong("earliest-delivery-after-order-received-millis")

  def deliveryWindow:(Long,Long) = (EarliestDeliveryAfterOrderReceivedMillis,DeliveryTimeWindowMillis)

}

object CourierConfig extends ExtensionId[CourierConfig] with ExtensionIdProvider {

  override def lookup = DispatcherConfig

  override def createExtension(system: ExtendedActorSystem) =
    new CourierConfig(system.settings.config.getConfig("reactive.dispatcher.courier"))

  override def get(system: ActorSystem): CourierConfig = super.get(system)
}