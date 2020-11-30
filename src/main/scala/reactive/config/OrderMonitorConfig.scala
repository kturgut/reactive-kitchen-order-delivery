package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

class OrderMonitorConfig(config: Config) extends Extension {
  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val MaxOrderLifeCycleCacheSize: Int = config.getInt("maximum-order-life-cycle-cache-size")
  val MaxEventsWithoutCheckpoint: Int = config.getInt("maximum-events-without-checkpoint")
  val InactivityShutdownTimer: FiniteDuration = config.getDuration("inactivity-shutdown-timer-delay-millis")
}

object OrderMonitorConfig extends ExtensionId[OrderMonitorConfig] with ExtensionIdProvider {

  override def lookup = OrderMonitorConfig

  override def createExtension(system: ExtendedActorSystem) =
    new OrderMonitorConfig(system.settings.config.getConfig("reactive.orderMonitor"))

  override def get(system: ActorSystem): OrderMonitorConfig = super.get(system)
}
