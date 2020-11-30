package reactive.config

import akka.actor.Actor
import akka.util.Timeout

import scala.concurrent.duration.DurationInt

trait ConfigBase {

  val timeout: Timeout = Timeout(300 milliseconds)

}

trait Configs {
  self: Actor =>

  val kitchenConf = KitchenConfig(context.system)
  val courierConf = CourierConfig(context.system)
  val dispatcherConf = DispatcherConfig(context.system)
  val customerConf = CustomerConfig(context.system)
  val orderProcessorConf = OrderProcessorConfig(context.system)
  val orderMonitorConf = OrderMonitorConfig(context.system)
  val shelfManagerConf = ShelfManagerConfig(context.system)
  val coordinatorConf = CoordinatorConfig(context.system)

}
