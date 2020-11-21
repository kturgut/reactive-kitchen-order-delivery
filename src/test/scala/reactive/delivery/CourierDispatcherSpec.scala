package reactive.delivery

import akka.actor.Props
import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.ReactiveKitchens.CourierDispatcherActorName
import reactive.kitchen.Kitchen.KitchenReadyForService

class CourierDispatcherSpec extends BaseSpec {

  "A CourierDispatcher Actor" should {
    val orderProcessor = TestProbe(OrderProcessorName)
    val shelfManager = TestProbe(ShelfManagerName)
    val kitchen = TestProbe(KitchenName)
    val kitchenReadyNotice = KitchenReadyForService(KitchenName, 2, kitchen.ref, orderProcessor.ref, shelfManager.ref)
    "initialize itself with KitchenReadyForService message" in {
      val dispatcher = system.actorOf(Props[CourierDispatcher],CourierDispatcherActorName)
      dispatcher ! kitchenReadyNotice
    }
  }
}