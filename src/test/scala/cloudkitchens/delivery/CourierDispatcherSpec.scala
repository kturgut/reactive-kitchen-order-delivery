package cloudkitchens.delivery

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import cloudkitchens.CloudKitchens.{CourierDispatcherActorName, OrderProcessorActorName}
import cloudkitchens.TestSpecHelper
import cloudkitchens.delivery.Courier.CourierAssignment
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService
import cloudkitchens.order.OrderProcessor
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class CourierDispatcherSpec extends TestKit (ActorSystem("TestActorSystem")) with ImplicitSender with TestSpecHelper
  with WordSpecLike
  with BeforeAndAfterAll{

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
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