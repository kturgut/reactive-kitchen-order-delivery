package reactive.delivery

import java.time.LocalDateTime

import akka.actor.{ActorRef, Cancellable, Props}
import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.delivery.Courier._
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder

import scala.concurrent.duration.Duration


class CourierDispatcherSpec extends BaseSpec {

  "Dispatcher " should {
    "receive CourierAssignment followed by Available messages from Courier when courier accepts and completes the delivery order" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val customer = TestProbe(CustomerName)

      val (proxy, dispatcher, courier) = dispatcherWithImpatientCourier(orderMonitor.ref, shelfManager.ref)
      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val acceptance = DeliveryAcceptance(product1.order, "Thanks", 5)
      val pickup = Pickup(product1)
      val deliveryComplete = DeliveryComplete(assignment1, pickup.product, acceptance)

      proxy.send(dispatcher, product1)
      val assignment = proxy.expectMsgType[CourierAssignment]
      assert(assignment.order == product1.order)
      assert(assignment.courierName == CourierName)
      shelfManager.expectMsgType[PickupRequest]
      shelfManager.reply(Pickup(product1))
      proxy.expectMsgType[CourierAssignment]
      customer.expectMsgType[DeliveryAcceptanceRequest]
      customer.reply(acceptance)
      proxy.expectMsgType[Available]
      val received = orderMonitor.expectMsgType[DeliveryComplete]
      assert(received.acceptance == deliveryComplete.acceptance)
      assert(received.assignment.courierName == deliveryComplete.assignment.courierName)
      assert(received.assignment.order == deliveryComplete.assignment.order)
    }

    "receive Available messages from Courier when order he is working on is discarded" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val customer = TestProbe(CustomerName)

      val (proxy, dispatcher, courier) = dispatcherWithImpatientCourier(orderMonitor.ref, shelfManager.ref)
      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val acceptance = DeliveryAcceptance(product1.order, "Thanks", 5)
      val discard = DiscardOrder(product1.order, "JustToTest", LocalDateTime.now())


      proxy.send(dispatcher, product1)
      val assignment = proxy.expectMsgType[CourierAssignment]
      assert(assignment.order == product1.order)
      assert(assignment.courierName == CourierName)
      shelfManager.expectMsgType[PickupRequest]
      proxy.expectMsgType[CourierAssignment]
      shelfManager.reply(discard)
      proxy.expectMsgType[Available]
      customer.expectNoMessage()
    }

    "receive DeclineOrder from Courier if Courier is already on assignment" in {
      val orderMonitor = TestProbe(OrderMonitorName)
      val shelfManager = TestProbe(ShelfManagerName)
      val customer = TestProbe(CustomerName)

      val (proxy, dispatcher, courier) = dispatcherWithImpatientCourier(orderMonitor.ref, shelfManager.ref)

      def trySendingProduct(product: PackagedProduct) = {
        // delay is needed due to proxy implementation between parent child
        Thread.sleep(10)
        proxy.send(dispatcher, product)
        Thread.sleep(10)
        val declineExpected = proxy.expectMsgType[DeclineCourierAssignment]
        assert(declineExpected.product == product)
      }

      val (product1, assignment1) = samplePackagedProductAndAssignment(1, customer.ref, courier)
      val (product2, _) = samplePackagedProductAndAssignment(2, customer.ref, courier)
      val (product3, _) = samplePackagedProductAndAssignment(3, customer.ref, courier)
      val (product4, _) = samplePackagedProductAndAssignment(4, customer.ref, courier)
      val (product5, _) = samplePackagedProductAndAssignment(5, customer.ref, courier)
      val acceptance = DeliveryAcceptance(product1.order, "Thanks", 5)
      val pickup = Pickup(product1)
      val deliveryComplete = DeliveryComplete(assignment1, pickup.product, acceptance)

      proxy.send(dispatcher, product1)
      println(proxy.expectMsgType[CourierAssignment])

      proxy.send(dispatcher, product2)
      val assignment = proxy.expectMsgType[CourierAssignment]
      assert(assignment.order == product1.order)
      val decline = proxy.expectMsgType[DeclineCourierAssignment]
      assert(decline.product == product2)
      trySendingProduct(product2)
      trySendingProduct(product3)
      shelfManager.expectMsgType[PickupRequest]
      shelfManager.reply(Pickup(product1))
      trySendingProduct(product4)
      customer.expectMsgType[DeliveryAcceptanceRequest]
      trySendingProduct(product5)
      customer.reply(acceptance)
      proxy.expectMsgType[Available]
      val received = orderMonitor.expectMsgType[DeliveryComplete]
      assert(received.product.order == deliveryComplete.assignment.order)
      assert(received.product.order == product1.order)
      proxy.send(dispatcher, product2)
      proxy.expectMsgType[CourierAssignment]
    }
  }

  /**
   * Creates Courier under Dispatcher ActorContext
   * Returns testProbe that can act as proxy between dispatcher and courier
   * (proxy,dispatcher,courier)
   */
  def dispatcherWithImpatientCourier(orderMonitor: ActorRef, shelfManager: ActorRef): (TestProbe, ActorRef, ActorRef) = {
    val proxy = TestProbe()
    var courier: ActorRef = TestProbe().ref
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val dispatcher = system.actorOf(Props(new Dispatcher {
      courier = context.actorOf(Props(new Courier(CourierName, orderMonitor, shelfManager) {
        override def reminderToDeliver(courierActorRefToRemind: ActorRef): Cancellable = {
          courierActorRefToRemind ! DeliverNow
          context.system.scheduler.scheduleOnce(Duration.Zero) {
          }
        }
      }), CourierName)
      override val receive = {
        case x if sender == courier => proxy.ref forward x
        case x => courier forward x
      }
    }))
    (proxy, dispatcher, courier)
  }


}