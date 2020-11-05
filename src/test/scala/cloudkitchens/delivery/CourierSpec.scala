package cloudkitchens.delivery

import akka.testkit.{TestActorRef, TestProbe}
import cloudkitchens.BaseSpec
import cloudkitchens.delivery.Courier._

import scala.concurrent.duration.DurationInt

class CourierSpec extends BaseSpec {

  "A CourierActor" should {
    "send an Assignment to OrderProcessor and ShelfManager when available" in {
      val orderProcessor = TestProbe(OrderProcessorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val courier = system.actorOf(Courier.props(CourierName, orderProcessor.ref, shelfManager.ref))
      val (product, assignment) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      assertEquals(shelfManager.expectMsgType[CourierAssignment], assignment)
    }
    "declare itself unavailable to parent CourierDispatcher, after it receives a delivery order" in {
      val orderProcessor = TestProbe(OrderProcessorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val (product, _) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      dispatcher.expectMsg(OnAssignment(courier))
    }
    "successfully deliver order to customer within 2 to 6 seconds, happy path" in {
      val orderProcessor = TestProbe(OrderProcessorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val customer = TestProbe()
      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val (product2) = samplePackagedProductAndAssignment(2, customer.ref, courier)
      courier ! product1
      dispatcher.expectMsg(OnAssignment(courier))
      val receivedAssignment = shelfManager.expectMsgType[CourierAssignment]
      assert(assignment1.courierRef == courier)
      assert(receivedAssignment.courierRef == courier)
      within(2 second, 6 second) {
        val request = shelfManager.expectMsgType[PickupRequest]
        assert(request.assignment.order.id == receivedAssignment.order.id)
        assert(request.assignment.courierRef == receivedAssignment.courierRef)
        shelfManager.reply(Pickup(product1))

        customer.expectMsgType[DeliveryAcceptanceRequest]
        customer.reply(DeliveryAcceptance(product1.order, "Happy Customer", 5))
        dispatcher.expectMsgType[Available]
      }
    }
    "should decline additional work while on assignment" in {
      val orderProcessor = TestProbe(OrderProcessorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val customer = TestProbe()
      val (product1, _) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val (product2, _) = samplePackagedProductAndAssignment(2, customer.ref, courier)
      courier ! product1
      dispatcher.expectMsg(OnAssignment(courier))

      courier ! product2
      dispatcher.expectMsgType[DeclineCourierAssignment]

      shelfManager.expectMsgType[CourierAssignment]
      within(2 second, 6 second) {

        courier ! product2
        dispatcher.expectMsgType[DeclineCourierAssignment]

        shelfManager.expectMsgType[PickupRequest]
        shelfManager.reply(Pickup(product1))

        courier ! product2
        dispatcher.expectMsgType[DeclineCourierAssignment]

        customer.expectMsgType[DeliveryAcceptanceRequest]
        customer.reply(DeliveryAcceptance(product1.order, "Happy Customer", 5))
        dispatcher.expectMsgType[Available]
      }
      courier ! product2
      dispatcher.expectMsgType[OnAssignment]
    }
  }
}


