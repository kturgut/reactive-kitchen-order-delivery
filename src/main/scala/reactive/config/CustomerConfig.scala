package reactive.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}


class CustomerConfig(config: Config) extends Extension {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  val onTimeDeliveryRecommendedTip:Int = config.getInt("on-time-delivery-recommended-tip")
  val lateDeliveryRecommendedTip:Int = config.getInt("on-time-delivery-recommended-tip")
  val customerHappinessInMillisThreshold:FiniteDuration = config.getDuration("customer-happiness-in-millis-threshold")
  val simulationOrderFilePath:String = config.getString("simulation-order-file-path")

}

object CustomerConfig extends ExtensionId[CustomerConfig] with ExtensionIdProvider {

  override def lookup = CustomerConfig

  override def createExtension(system: ExtendedActorSystem) = {
    new CustomerConfig(system.settings.config.getConfig("reactive.customer"))
  }

  override def get(system: ActorSystem): CustomerConfig = super.get(system)
}
