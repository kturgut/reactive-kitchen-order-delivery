package reactive.delivery

import akka.actor.Props
import akka.testkit.TestProbe
import reactive.{BaseSpec, DispatcherActor}


class CourierDispatcherSpec extends BaseSpec {

  "A CourierDispatcher Actor" should {
    val orderProcessor = TestProbe(OrderProcessorName)
    val shelfManager = TestProbe(ShelfManagerName)
    val kitchen = TestProbe(KitchenName)
    val monitor = TestProbe(MonitorName)
    val dispatcher = TestProbe(KitchenName)
    "initialize itself with KitchenReadyForService message" in {
      val dispatcher = system.actorOf(Props[Dispatcher], DispatcherActor)
      dispatcher ! "Hello There. Finish ME!"
    }
  }
}