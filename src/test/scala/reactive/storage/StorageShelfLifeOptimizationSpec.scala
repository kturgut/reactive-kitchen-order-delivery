package reactive.storage

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.event.NoLogging
import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.order.Temperature

import scala.collection.mutable

class StorageShelfLifeOptimizationSpec extends BaseSpec with StorageHelper {


  "A Storage" should {

    val customer = TestProbe(CustomerName)
    val cold = Shelf.cold(3)

    val time = LocalDateTime.now()

    val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 10) yield {
      samplePackagedProduct(i, customer.ref, i * 10, 0.5f, Hot, time.plus(i * 100, ChronoUnit.MILLIS))
    }

    "store a single hot product and it should go to hot shelf " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.Cold -> cold, Temperature.All -> overflow))

      assert(storage.totalCapacity == 10)
      assert(storage.totalProductsOnShelves == 0)
      assert(storage.overflow.hasAvailableSpace)
      storage.putPackageOnShelf(hots(0))
      assert(hot.size == 1)
      assert(overflow.size == 0)
    }

    "store multiple hot products and they should all go to hot shelf till it is full " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))

      storage.putPackageOnShelf(hots(0), time)
      storage.putPackageOnShelf(hots(1), time)
      storage.putPackageOnShelf(hots(2), time)
      assert(hot.size == 3)
      storage.putPackageOnShelf(hots(3), time)
      storage.putPackageOnShelf(hots(4), time)
      assert(hot.size == 3)
      assert(overflow.size == 2)
      storage.putPackageOnShelf(hots(5), time)
      storage.putPackageOnShelf(hots(6), time)
      assert(hot.size == 3)
      assert(overflow.size == 4)
      assert(!overflow.hasAvailableSpace)
      assert(!storage.overflow.hasAvailableSpace)
      (0 to 2) forall { i => hot.products.contains(hots(i)) }
      (3 to 6) forall { i => overflow.products.contains(hots(i)) }
    }

    "if new product added when full, if and no order is guaranteed to expire before delivery it should discard the most recent order in overflow shelf" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.Cold -> cold, Temperature.All -> overflow))

      (0 to 6) foreach { a => storage.putPackageOnShelf(hots(a), time) }
      assert(!hot.hasAvailableSpace)
      assert(!overflow.hasAvailableSpace)
      assert(overflow.productsDecreasingByOrderDate.head == hots(6))
      print(hot)
      print(overflow)
      val discarded = storage.putPackageOnShelf(hots(7), time)
      assert(discarded.leftSide.nonEmpty)
      assert(discarded.leftSide.head.order == hots(7).order)
    }


    def print(shelf: Shelf): Unit = {
      println(s"${shelf.name} shelf capacity utilization:${shelf.products.size} / ${shelf.capacity}, decay rate modifier:${shelf.decayModifier}")
      println(s"       contents: ${shelf.products.map(product => (product.order.id, product.order.name, product.value)).mkString(",")}")
    }

  }

}
