package reactive.order

import java.time.temporal.ChronoUnit

import akka.event.NoLogging
import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.delivery.Courier.{CourierAssignment, DeliveryAcceptance, DeliveryComplete}
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.{DiscardOrder, ExpiredShelfLife}

class OrderLifeCycleSpec extends BaseSpec {

  "An OrderLifeCycle" should {

    val customer = TestProbe(CustomerName)
    val courier = TestProbe(CourierName)

    val order = Order("1", "Ayran", "hot", 10, 0.3f, customer.ref, fixedTime)
    val time1 = fixedTime.plus(1, ChronoUnit.SECONDS)
    val time2 = fixedTime.plus(2, ChronoUnit.SECONDS)
    val time3 = fixedTime.plus(3, ChronoUnit.SECONDS)
    val time4 = fixedTime.plus(4, ChronoUnit.SECONDS)

    val packagedProduct = PackagedProduct(order, (2000, 6000), time1)
    val agedProduct = packagedProduct.phantomCopy(2, time3)

    val discardOrder = DiscardOrder(order, ExpiredShelfLife, time2)
    val courierAssignment = CourierAssignment(order, CourierName, courier.ref, time3)
    val acceptance = DeliveryAcceptance(order, "Thanks", 5, time4)
    val deliveryComplete = DeliveryComplete(courierAssignment, agedProduct, acceptance, time4)

    val lifeCycle = OrderLifeCycle(order)

    "be able to shortPrint itself correctly if no other event update received" in {
      assert(lifeCycle.toShortString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00")
      assert(lifeCycle.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00")
    }

    "update itself when PackagedProduct is received" in {
      val produced = lifeCycle.update(packagedProduct, NoLogging)
      assert(produced.toShortString() == "Order id:1 'Ayran' shelfLife:10 produced on:06:00:01")
      assert(produced.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01")
    }

    "update itself when DiscardOrder is received without PackagedProduct" in {
      val discarded = lifeCycle.update(discardOrder, NoLogging)
      assert(discarded.toShortString() == "Order id:1 'Ayran' shelfLife:10 discarded:ExpiredShelfLife on:06:00:02")
      assert(discarded.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 discarded:ExpiredShelfLife on:06:00:02")
    }

    "update itself when DiscardOrder is received after PackagedProduct" in {
      val produced = lifeCycle.update(packagedProduct, NoLogging)
      val discarded = produced.update(discardOrder, NoLogging)
      assert(discarded.toShortString() == "Order id:1 'Ayran' shelfLife:10 discarded:ExpiredShelfLife on:06:00:02")
      assert(discarded.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01 discarded:ExpiredShelfLife on:06:00:02")
    }

    "update itself when DeliveryComplete is received without PackagedProduct" in {
      val delivered = lifeCycle.update(deliveryComplete, NoLogging)
      assert(delivered.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(delivered.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 delivered value:0.68 tips:5 on:06:00:04")
    }

    "update itself when DeliveryComplete is received after PackagedProduct" in {
      val produced = lifeCycle.update(packagedProduct, NoLogging)
      val delivered = produced.update(deliveryComplete, NoLogging)
      assert(delivered.toShortString() == "Order id:1 'Ayran' shelfLife:10 delivered value:0.68 tips:5 on:06:00:04")
      assert(delivered.toString() == "Order id:1 'Ayran' shelfLife:10 received on:06:00:00 produced on:06:00:01 delivered value:0.68 tips:5 on:06:00:04")
    }


  }


}
