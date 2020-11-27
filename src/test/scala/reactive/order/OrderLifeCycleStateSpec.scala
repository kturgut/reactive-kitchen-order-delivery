package reactive.order

import java.time.temporal.ChronoUnit

import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.delivery.Courier.{CourierAssignment, DeliveryAcceptance, DeliveryComplete}
import reactive.order.OrderMonitor._
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.{DiscardOrder, ExpiredShelfLife}

class OrderLifeCycleStateSpec extends BaseSpec {

  "An OrderLifeCycleState" should {

    val customer = TestProbe(CustomerName)
    val courier = TestProbe(CourierName)

    val cacheSize = 2

    val order = Order("1", "Ayran", "hot", 10, 0.3f, customer.ref, fixedTime)
    val time1 = fixedTime.plus(1, ChronoUnit.SECONDS)
    val time2 = fixedTime.plus(2, ChronoUnit.SECONDS)
    val time3 = fixedTime.plus(3, ChronoUnit.SECONDS)
    val time4 = fixedTime.plus(4, ChronoUnit.SECONDS)
    val time5 = fixedTime.plus(5, ChronoUnit.SECONDS)

    val packagedProduct = PackagedProduct(order, (2000, 6000), time1)
    val agedProduct = packagedProduct.phantomCopy(2, time3)

    val discardOrder = DiscardOrder(order, ExpiredShelfLife, time2)
    val courierAssignment = CourierAssignment(order, CourierName, courier.ref, time3)
    val acceptance = DeliveryAcceptance(order, "Thanks", 5, time4)
    val deliveryComplete = DeliveryComplete(courierAssignment, agedProduct, acceptance, time4)

    val orderRecord = OrderRecord(time1, order)
    val productRecord = ProductRecord(time2, agedProduct)
    val deliveryCompleteRecord = DeliveryCompleteRecord(time5, deliveryComplete)
    val discardOrderRecord = DiscardOrderRecord(time5, discardOrder)

    val state = OrderLifeCycleState()

    "initialize properly" in {
      assert(state.activeOrders.size == 0)
      assert(state.eventCounter == 0)
      assert(state.totalTipsReceived == 0)
      assert(state.activeOrders.size == 0)
      assert(state.orderCounter == 0)
      assert(state.productionCounter == 0)
    }

    "be able to add an order record into cache" in {
      val updated = state.update(orderRecord, cacheSize)
      assert(updated.eventCounter == 1)
      assert(updated.totalTipsReceived == 0)
      assert(updated.activeOrders.size == 1)
      assert(updated.orderCounter == 1)
      assert(state.productionCounter == 0)
      val orderRecordInCacheOption = updated.activeOrders.get(orderRecord.order.id)
      assert(orderRecordInCacheOption.isDefined && orderRecord.order == orderRecordInCacheOption.get.order)
      assert(orderRecordInCacheOption.get.toShortString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00")
    }

    "update itself when ProductRecord is received for after OrderRecord" in {
      val updated = state.update(orderRecord, cacheSize)
      val withProduct = updated.update(productRecord, cacheSize)
      assert(withProduct.eventCounter == 2)
      assert(withProduct.totalTipsReceived == 0)
      assert(withProduct.activeOrders.size == 1)
      assert(withProduct.orderCounter == 1)
      assert(withProduct.productionCounter == 1)
      val lifeCycleOption = withProduct.activeOrders.get(orderRecord.order.id)
      assert(lifeCycleOption.isDefined)
      val lifeCycle = lifeCycleOption.get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 produced on:06:00:01")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01")
    }

    "update itself when DiscardOrderRecord is received after PackagedProduct" in {
      val updated = state.update(orderRecord, cacheSize)
      val withProduct = updated.update(productRecord, cacheSize)
      val withDiscardOrder = withProduct.update(discardOrderRecord, cacheSize)
      assert(withDiscardOrder.eventCounter == 3)
      assert(withDiscardOrder.totalTipsReceived == 0)
      assert(withDiscardOrder.activeOrders.size == 1)
      assert(withDiscardOrder.orderCounter == 1)
      assert(withDiscardOrder.productionCounter == 1)
      val lifeCycleOption = withDiscardOrder.activeOrders.get(orderRecord.order.id)
      assert(lifeCycleOption.isDefined)
      val lifeCycle = lifeCycleOption.get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 discarded:ExpiredShelfLife on:06:00:02")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01 discarded:ExpiredShelfLife on:06:00:02")
    }

    "update itself when DeliveryComplete is received after PackagedProduct" in {
      val updated = state.update(orderRecord, cacheSize)
      val withProduct = updated.update(productRecord, cacheSize)
      val withDeliveryComplete = withProduct.update(deliveryCompleteRecord, cacheSize)
      assert(withDeliveryComplete.eventCounter == 3)
      assert(withDeliveryComplete.totalTipsReceived == 5)
      assert(withDeliveryComplete.activeOrders.size == 1)
      assert(withDeliveryComplete.orderCounter == 1)
      assert(withDeliveryComplete.productionCounter == 1)
      val lifeCycleOption = withDeliveryComplete.activeOrders.get(orderRecord.order.id)
      assert(lifeCycleOption.isDefined)
      val lifeCycle = lifeCycleOption.get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01 delivered value:0.68 tips:5 on:06:00:04")
    }

    "update itself properly when events come out of order" in {
      var updated = state.update(deliveryCompleteRecord, cacheSize)
      var lifeCycleOption = updated.activeOrders.get(orderRecord.order.id)
      assert(lifeCycleOption.isDefined)
      var lifeCycle = lifeCycleOption.get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 delivered value:0.68 tips:5 on:06:00:04")
      assert(updated.activeOrders.size == 1)
      assert(updated.eventCounter == 1)

      updated = updated.update(orderRecord, cacheSize)
      lifeCycle = updated.activeOrders.get(orderRecord.order.id).get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 delivered value:0.68 tips:5 on:06:00:04")
      assert(updated.activeOrders.size == 1)
      assert(updated.eventCounter == 2)

      updated = updated.update(productRecord, cacheSize)
      lifeCycle = updated.activeOrders.get(orderRecord.order.id).get
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01 delivered value:0.68 tips:5 on:06:00:04")

      assert(updated.eventCounter == 3)
      assert(updated.activeOrders.size == 1)
      assert(updated.orderCounter == 1)
      assert(updated.productionCounter == 1)
    }


    "should keep FIFO cache size constant, removing earliest received completed orders first" in {
      val order0 = Order("0", "Doner", "hot", 5, 0.5f, customer.ref, fixedTime)
      val orderRecord0 = OrderRecord(time1, order0)

      var updated = state.update(orderRecord0, cacheSize)
      updated = updated.update(orderRecord, cacheSize)

      assert(updated.eventCounter == 2)
      assert(updated.activeOrders.size == 2)
      assert(updated.orderCounter == 2)
      assert(updated.productionCounter == 0)

      updated = updated.update(productRecord, cacheSize)
      updated = updated.update(deliveryCompleteRecord, cacheSize)
      assert(updated.eventCounter == 4)
      assert(updated.activeOrders.size == cacheSize)
      assert(updated.activeOrders.keys.toSet == (order :: order0 :: Nil).map(_.id).toSet)

      val order2 = Order("2", "Imambayildi", "hot", 5, 0.7f, customer.ref, fixedTime)
      val orderRecord2 = OrderRecord(time1, order2)

      updated = updated.update(orderRecord2, cacheSize)
      assert(updated.activeOrders.size == 2)
      assert(updated.activeOrders.keys.toSet == (order0 :: order2 :: Nil).map(_.id).toSet)
    }
  }

}
