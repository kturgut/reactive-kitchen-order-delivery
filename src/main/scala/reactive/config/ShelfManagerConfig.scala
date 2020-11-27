package reactive.config


import akka.actor.{Actor, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

case class ShelfConfig(config: Config) {

  val HotShelfCapacity = config.getInt("hot-shelf-capacity")
  val ColdShelfCapacity = config.getInt("cold-shelf-capacity")
  val FrozenShelfCapacity = config.getInt("frozen-shelf-capacity")
  val OverflowShelfCapacity = config.getInt("overflow-shelf-capacity")

  val HotShelfDecayModifier = config.getInt("hot-shelf-decay-modifier")
  val ColdShelfDecayModifier = config.getInt("cold-shelf-decay-modifier")
  val FrozenShelfDecayModifier = config.getInt("frozen-shelf-decay-modifier")
  val OverflowShelfDecayModifier = config.getInt("overflow-shelf-decay-modifier")
}


class ShelfManagerConfig(config: Config) extends Extension {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val CriticalTimeThresholdForSwappingInMillis: FiniteDuration = config.getDuration("critical-time-threshold-for-swapping-millis")

  val OverflowUtilizationSafetyThreshold = config.getDouble("overflow-utilization-safety-threshold").toFloat

  val OverflowUtilizationReportingThreshold = config.getDouble("overflow-utilization-reporting-threshold").toFloat

  val MaximumCourierAssignmentCacheSize = config.getInt("max-courier-assignment-cache-size")

  val ShelfLifeOptimizationTimerDelay = config.getDuration("shelf-life-optimization-timer-delay-millis")

  def shelfConfig() = ShelfConfig(config.getConfig("shelf"))
}


object ShelfManagerConfig extends ExtensionId[ShelfManagerConfig] with ExtensionIdProvider {

  override def lookup = ShelfManagerConfig

  override def createExtension(system: ExtendedActorSystem) =
    new ShelfManagerConfig(system.settings.config.getConfig("reactive.shelfManager"))

  override def get(system: ActorSystem): ShelfManagerConfig = super.get(system)
}
