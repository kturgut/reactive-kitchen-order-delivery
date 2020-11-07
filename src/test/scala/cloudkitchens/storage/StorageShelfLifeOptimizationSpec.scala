package cloudkitchens.storage

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.event.NoLogging
import akka.testkit.TestProbe
import cloudkitchens.BaseSpec
import cloudkitchens.customer.Customer
import cloudkitchens.order.Temperature
import cloudkitchens.storage.ShelfManager.DiscardOrder

import scala.collection.mutable

class StorageShelfLifeOptimizationSpec extends BaseSpec with StorageHelper {


  "A Storage" should {

    val customer = TestProbe(CustomerName)
    val cold = Shelf.cold(3)

    val time = LocalDateTime.now()

    val hots:IndexedSeq[PackagedProduct] = for (i<- 0 until 10) yield {samplePackagedProduct(i, customer.ref, i * 10, 0.5f , Hot, time.plus(i*100,ChronoUnit.MILLIS))}

    "store a single hot product and it should go to hot shelf " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot,Temperature.Cold->cold, Temperature.All->overflow))

      assert(storage.totalCapacity == 10)
      assert(storage.totalProductsOnShelves == 0)
      assert(!storage.isOverflowFull())
      storage.putPackageOnShelf(hots(0))
      assert(hot.size == 1)
      assert(overflow.size == 0)
    }

    "store multiple hot products and they should all go to hot shelf till it is full " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot, Temperature.All->overflow))

      storage.putPackageOnShelf(hots(0),time)
      storage.putPackageOnShelf(hots(1),time)
      storage.putPackageOnShelf(hots(2),time)
      assert(hot.size == 3)
      storage.putPackageOnShelf(hots(3),time)
      storage.putPackageOnShelf(hots(4),time)
      assert(hot.size == 3)
      assert(overflow.size == 2)
      storage.putPackageOnShelf(hots(5),time)
      storage.putPackageOnShelf(hots(6),time)
      assert(hot.size == 3)
      assert(overflow.size == 4)
      assert(!overflow.hasAvailableSpace)
      assert(storage.isOverflowFull())
      (0 to 2) forall {i=>hot.products.contains(hots(i))}
      (3 to 6) forall {i=>overflow.products.contains(hots(i))}
    }

    "if new product added when full, if and no order is guaranteed to expire before delivery it should discard the most recent order in overflow shelf" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot,Temperature.Cold->cold, Temperature.All->overflow))

      (0 to 6) foreach {a=>storage.putPackageOnShelf(hots(a),time)}
      assert(!hot.hasAvailableSpace)
      assert(!overflow.hasAvailableSpace)
      assert(overflow.productsDecreasingByOrderDate.head == hots(6))
      print(hot)
      print(overflow)
      val discarded = storage.putPackageOnShelf(hots(7),time)
      assert(discarded.leftSide.nonEmpty)
      assert(discarded.leftSide.head.order == hots(7).order)
    }

    "if new product added when full, any product that is guaranteed to miss its delivery window before expiration should be discarded first" in {
     // TODO
    }



//    "should be able to age products on its shelves over time.. without optimization kicking in" in {
//      val hot = Shelf.hot(3)
//      val overflow = Shelf.overflow(4)
//      var storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))
//      val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 5) yield {
//        samplePackagedProduct(i, customer.ref, i, 0.5f, Hot, time)
//      }
//      hots.foreach(product => storage.putPackageOnShelf(product, product.createdOn))
//
//      val actual = (for (timeIncrement <- 0 to 1000 by 500) yield {
//        storage = storage.snapshot(time.plus(timeIncrement, ChronoUnit.MILLIS))
//        report(storage)
//        timeIncrement -> collectStorageData(storage)
//      }).toMap
//      val expected = {
//        Map(
//          0 -> Map(Temperature.Hot -> List(("0", 0.0f, 1.0f), ("1", 1.0f, 1.0f), ("2", 2.0f, 1.0f)), Temperature.All -> List(("3", 3.0f, 1.0f), ("4", 4.0f, 1.0f))),
//          500 -> Map(Temperature.Hot -> List(("0", 0.0f, 0.0f), ("1", 0.25f, 0.25f), ("2", 1.25f, 0.625f)), Temperature.All -> List(("3", 2.0f, 0.6666667f), ("4", 3.0f, 0.75f))),
//          1000 -> Map(Temperature.Hot -> List(("0", 0.0f, 0.0f), ("1", 0.0f, 0.0f), ("2", 0.5f, 0.25f)), Temperature.All -> List(("3", 1.0f, 0.33333334f), ("4", 2.0f, 0.5f)))
//        )
//      }
//      assert(actual == expected)
//    }



    def print(shelf:Shelf): Unit = {
      println(s"${shelf.name} shelf capacity utilization:${shelf.products.size} / ${shelf.capacity}, decay rate modifier:${shelf.decayModifier}")
      println(s"       contents: ${shelf.products.map(product=>(product.order.id, product.order.name,product.value)).mkString(",")}")
    }

  }

}
