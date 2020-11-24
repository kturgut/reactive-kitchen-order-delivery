package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}




class CoordinatorConfig(config: Config) extends Extension {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val heartBeatScheduleMillis:FiniteDuration =
    config.getDuration("heart-beat-schedule-millis")

  val initializationTimeInMillis:FiniteDuration = config.
    getDuration("initialization-time-millis")

}

object CoordinatorConfig extends ExtensionId[CoordinatorConfig] with ExtensionIdProvider {

  override def lookup = CoordinatorConfig

  override def createExtension(system: ExtendedActorSystem) = {
    new CoordinatorConfig(system.settings.config.getConfig("reactive.coordinator"))
  }

  override def get(system: ActorSystem): CoordinatorConfig = super.get(system)
}
