package reactive.delivery

import java.time.LocalDateTime

import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import reactive.BaseSpec
import reactive.delivery.Courier._
import reactive.storage.ShelfManager.DiscardOrder

import scala.concurrent.duration.Duration

class CourierSpec extends BaseSpec {

  "A CourierActor" should {
    "send an Assignment to OrderProcessor and ShelfManager when available" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val kitchen = TestProbe(KitchenName)
      val courier = system.actorOf(Courier.props(CourierName, orderMonitor.ref, shelfManager.ref))
      val (product, assignment) = samplePackagedProductAndAssignment(1, orderMonitor.ref, courier)
      courier ! product
      assertEquals(expectMsgType[CourierAssignment], assignment)
    }

    "declare itself unavailable to parent CourierDispatcher, after it receives a delivery order" in {
      val orderProcessor = TestProbe(OrderProcessorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val dispatcher = TestProbe()
      val courier = TestActorRef(Courier.props(CourierName,
        orderProcessor.ref, shelfManager.ref), dispatcher.ref, CourierName)
      val (product, _) = samplePackagedProductAndAssignment(1, orderProcessor.ref, courier)
      courier ! product
      dispatcher.expectMsgType[CourierAssignment]
    }

    "successfully deliver order to customer within delivery window, happy path" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val courier = impatientCourier(orderMonitor.ref, shelfManager.ref)
      val customer = TestProbe()
      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val acceptance = DeliveryAcceptance(product1.order, "Thanks", 5)
      val pickup = Pickup(product1)
      val deliveryComplete = DeliveryComplete(assignment1, pickup.product, acceptance)

      courier ! product1
      expectMsgType[CourierAssignment]
      shelfManager.expectMsgType[PickupRequest]
      shelfManager.reply(pickup)
      customer.expectMsgType[DeliveryAcceptanceRequest]
      customer.reply(acceptance)
      val received = orderMonitor.expectMsgType[DeliveryComplete]
      assert(received.acceptance == deliveryComplete.acceptance)
      assert(received.assignment.courierName == deliveryComplete.assignment.courierName)
      assert(received.assignment.order == deliveryComplete.assignment.order)
      assert(received.product == deliveryComplete.product)
    }

    "become available after Shelf Manager returns a DiscardOrder and handle another order" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val courier = impatientCourier(orderMonitor.ref, shelfManager.ref)
      val customer = TestProbe()
      val (product1, _) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val (product2, _) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val discard = DiscardOrder(product1.order, "JustToTest", LocalDateTime.now())

      courier ! product1
      expectMsgType[CourierAssignment]
      shelfManager.expectMsgType[PickupRequest]
      shelfManager.reply(discard)
      courier ! product2
      // expectMsgType[CourierAssignment]
    }

  }


  /**
   * creates an impatient Courier under system context
   */
  def impatientCourier(orderMonitor: ActorRef, shelfManager: ActorRef) = {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    system.actorOf(Props(new Courier(CourierName, orderMonitor, shelfManager) {
      override def reminderToDeliver(courierActorRefToRemind: ActorRef): Cancellable = {
        courierActorRefToRemind ! DeliverNow
        context.system.scheduler.scheduleOnce(Duration.Zero) {
        }
      }
    }))
  }


  def customActorSystem(): ActorSystem = {
    ActorSystem("testsystem",
      ConfigFactory.parseString(
        """
              akka.loggers = ["akka.testkit.TestEventListener"]
      """))
  }
}


