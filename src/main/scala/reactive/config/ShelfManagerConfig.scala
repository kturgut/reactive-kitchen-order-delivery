package reactive.config


import akka.actor.{Actor, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

case class ShelfConfig(config: Config) {

  val hotShelfCapacity = config.getInt("hot-shelf-capacity")
  val coldShelfCapacity = config.getInt("cold-shelf-capacity")
  val frozenShelfCapacity = config.getInt("frozen-shelf-capacity")
  val overflowShelfCapacity = config.getInt("overflow-shelf-capacity")

  val hotShelfDecayModifier = config.getInt("hot-shelf-decay-modifier")
  val coldShelfDecayModifier = config.getInt("cold-shelf-decay-modifier")
  val frozenShelfDecayModifier = config.getInt("frozen-shelf-decay-modifier")
  val overflowShelfDecayModifier = config.getInt("overflow-shelf-decay-modifier")
}


class ShelfManagerConfig(config: Config) extends Extension {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val criticalTimeThresholdForSwappingInMillis: FiniteDuration = config.getDuration("critical-time-threshold-for-swapping-millis")

  val overflowUtilizationSafetyThreshold = config.getInt("overflow-utilization-safety-threshold")

  val overflowUtilizationReportingThreshold = config.getInt("overflow-utilization-reporting-threshold")

  val maximumCourierAssignmentCacheSize = config.getInt("max-courier-assignment-cache-size")

  val shelfLifeOptimizationTimerDelay = config.getDuration("shelf-life-optimization-timer-delay-millis")

  def shelfConfig() = ShelfConfig(config.getConfig("shelf"))
}


object ShelfManagerConfig extends ExtensionId[ShelfManagerConfig] with ExtensionIdProvider {

  override def lookup = ShelfManagerConfig

  override def createExtension(system: ExtendedActorSystem) =
    new ShelfManagerConfig(system.settings.config.getConfig("reactive.shelfManager"))

  override def get(system: ActorSystem): ShelfManagerConfig = super.get(system)
}
