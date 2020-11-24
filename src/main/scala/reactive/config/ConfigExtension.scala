//package reactive.config
//
//import akka.actor.{Actor, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
//import com.typesafe.config.Config
//
//class ConfigExtension(config: Config) extends Extension {
//  val DbUri: String = config.getString("heart-beat-schedule-millis")
//
//  def kitchenConf = KitchenConfig(config.getConfig("kitchen"))
//  def courierConf = CourierConfig(config.getConfig("courier"))
//  def dispatcherConf = DispatcherConfig(config.getConfig("dispatcher"))
//  def customerConf = CustomerConfig(config.getConfig("customer"))
//  def orderProcessorConf = OrderProcessorConfig(config.getConfig("orderProcessor"))
//  def orderMonitorConf = OrderMonitorConfig(config.getConfig("orderMonitor"))
//  def shelfManagerConf = ShelfManagerConfig(config.getConfig("shelfManager"))
//  def coordinatorConf = CoordinatorConfig(config.getConfig("coordinator"))
//
//}
//object ConfigExtension extends ExtensionId[ConfigExtension] with ExtensionIdProvider {
//
//  override def lookup = ConfigExtension
//
//  override def createExtension(system: ExtendedActorSystem) =
//    new ConfigExtension(system.settings.config.getConfig("reactive.coordinator"))
//
//  /**
//   * Java API: retrieve the Settings extension for the given system.
//   */
//  override def get(system: ActorSystem): ConfigExtension = super.get(system)
//}
//
//class TestActor extends Actor {
//  val settings = ConfigExtension(context.system)
//  override def receive: Receive  = {
//    case message => println(message + settings.DbUri)
//  }
//}
//
//object Test extends App {
//  val sys = ActorSystem("foo").actorOf(Props[TestActor])
//  sys ! "talk"
//}