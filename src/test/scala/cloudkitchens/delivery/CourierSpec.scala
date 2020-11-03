package cloudkitchens.delivery

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import cloudkitchens.TestSpecHelper
import cloudkitchens.delivery.Courier.{Available, CourierAssignment, DeclineCourierAssignment, OnAssignment, PickupRequest}
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration.DurationInt

class CourierSpec extends TestKit (ActorSystem("TestActorSystem")) with ImplicitSender with TestSpecHelper
  with WordSpecLike
  with BeforeAndAfterAll{

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
  "A CourierActor" should {
    val orderProcessor = TestProbe(OrderProcessorName)
    val shelfManager = TestProbe(ShelfManagerName)
    "send an Assignment to OrderProcessor and ShelfManager when available" in {
      val courier = system.actorOf(Courier.props(CourierName, orderProcessor.ref, shelfManager.ref))
      val (product, assignment) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      assertEquals(orderProcessor.expectMsgType[CourierAssignment], assignment)
      assertEquals(shelfManager.expectMsgType[CourierAssignment], assignment)
    }
    "declare itself unavailable to parent CourierDispatcher, after it receives a delivery order" in {
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val (product, _) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      dispatcher.expectMsg(OnAssignment(courier))
    }
    "send pickup request to shelfManager within 2 to 6 seconds after it received the delivery order" in {
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val (product, assignment) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      shelfManager.expectMsgType[CourierAssignment]
      within(2 second, 6 second) {
        val request = shelfManager.expectMsgType[PickupRequest]
        assert(request.assignment.order == assignment.order)
        assert(request.assignment.courierRef == assignment.courierRef)
      }
      dispatcher.expectMsg(OnAssignment(courier))
      dispatcher.expectMsg(Available(courier))
    }
    "decline a subsequent assignment when unavailable" in {
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val (product1, _) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      val (product2) = samplePackagedProductAndAssignment(2, orderProcessor.ref, courier)
      courier ! product1
      dispatcher.expectMsg(OnAssignment(courier))
      courier ! product2
      dispatcher.expectMsgType[DeclineCourierAssignment]
    }
  }
}


