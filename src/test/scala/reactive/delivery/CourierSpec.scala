package reactive.delivery

import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import reactive.BaseSpec
import reactive.delivery.Courier._

import scala.concurrent.duration.{Duration, DurationInt}

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
    "successfully deliver order to customer within delivery window, happy path" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val courier = impatientCourier(orderMonitor.ref,shelfManager.ref)
      val customer = TestProbe()
      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      courier ! product1
      //dispatcher.expectMsg(OnAssignment(courier))
      val receivedAssignment = shelfManager.expectMsgType[CourierAssignment]
      assert(assignment1.courierRef == courier)
      assert(receivedAssignment.courierRef == courier)
      val request = shelfManager.expectMsgType[PickupRequest]
      assert(request.assignment.order.id == receivedAssignment.order.id)
      assert(request.assignment.courierRef == receivedAssignment.courierRef)
      shelfManager.reply(Pickup(product1))

      customer.expectMsgType[DeliveryAcceptanceRequest]
      customer.reply(DeliveryAcceptance(product1.order, "Happy Customer", 5))
//        dispatcher.expectMsgType[Available]
    }
    "should decline additional work while on assignment" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val courier = impatientCourier(orderMonitor.ref,shelfManager.ref)
      val customer = TestProbe()
      val (product1, _) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val (product2, _) = samplePackagedProductAndAssignment(2, customer.ref, courier)
      courier ! product1
      courier ! product2
      shelfManager.expectMsgType[CourierAssignment]
      courier ! product2
      shelfManager.expectMsgType[PickupRequest]
      shelfManager.reply(Pickup(product1))
      courier ! product2
      customer.expectMsgType[DeliveryAcceptanceRequest]
      customer.reply(DeliveryAcceptance(product1.order, "Happy Customer", 5))
      courier ! product2
    }
  }

  def impatientCourier(orderMonitor:ActorRef,shelfManager:ActorRef) = {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    system.actorOf(Props(new Courier(CourierName, orderMonitor, shelfManager) {
    override def reminderToDeliver(courierActorRefToRemind: ActorRef): Cancellable = {
      courierActorRefToRemind ! DeliverNow
      context.system.scheduler.scheduleOnce(Duration.Zero) {
      }
    }
  }))}

//  private def createParentForwarder(probe: TestProbe, actorProps: Props): ActorRef = {
//    val parent = TestActorRef(new Actor {
//
//      val someActor = context.actorOf(actorProps, "someActor")
//
//      def receive = {
//        case x =>
//          probe.ref forward x
//
//      }
//    })
//
//    parent.underlying.child("someActor").get
//  }

  def customActorSystem():ActorSystem = {
    ActorSystem("testsystem",
            ConfigFactory.parseString("""
              akka.loggers = ["akka.testkit.TestEventListener"]
      """))
  }
}


