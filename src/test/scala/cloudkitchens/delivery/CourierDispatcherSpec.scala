package cloudkitchens.delivery

import akka.actor.Props
import akka.testkit.TestProbe
import cloudkitchens.BaseSpec
import cloudkitchens.CloudKitchens.CourierDispatcherActorName
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService

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