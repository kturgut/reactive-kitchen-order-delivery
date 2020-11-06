package cloudkitchens.storage

import java.time.LocalDateTime

import akka.event.NoLogging
import akka.testkit.TestProbe
import cloudkitchens.BaseSpec
import cloudkitchens.customer.Customer
import cloudkitchens.order.Temperature
import cloudkitchens.storage.ShelfManager.DiscardOrder

import scala.collection.mutable

class StorageShelfLifeOptimizationSpec extends BaseSpec {


  "A Storage" should {

    val customer = TestProbe(CustomerName)
    val cold = Shelf.cold(3)

    val hots:IndexedSeq[PackagedProduct] = for (i<- 1 to 10) yield {samplePackagedProduct(i, customer.ref, i * 10, 0.1f , Hot, fixedTime)}
    val colds:IndexedSeq[PackagedProduct] = for (i<- 1 to 10) yield {samplePackagedProduct(i, customer.ref, i * 10, 0.1f , Cold, fixedTime)}

    "store a single hot product and it should go to hot shelf " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot,Temperature.Cold->cold, Temperature.All->overflow))

      assert(storage.totalCapacity == 10)
      assert(storage.totalProductsOnShelves == 0)
      assert(storage.hasAvailableSpace)
      storage.putPackageOnShelf(hots(0))
      assert(hot.size == 1)
      assert(overflow.size == 0)
    }

    "store multiple hot products and they should all go to hot shelf till it is full " in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot,Temperature.Cold->cold, Temperature.All->overflow))

      storage.putPackageOnShelf(hots(0),fixedTime)
      storage.putPackageOnShelf(hots(1),fixedTime)
      storage.putPackageOnShelf(hots(2),fixedTime)
      assert(hot.size == 3)
      storage.putPackageOnShelf(hots(3),fixedTime)
      storage.putPackageOnShelf(hots(4),fixedTime)
      assert(hot.size == 3)
      assert(overflow.size == 2)
      storage.putPackageOnShelf(hots(5),fixedTime)
      storage.putPackageOnShelf(hots(6),fixedTime)
      assert(hot.size == 3)
      assert(overflow.size == 4)
      assert(!overflow.hasAvailableSpace)
      assert(storage.hasAvailableSpace)
      (0 to 2) forall {i=>hot.products.contains(hots(i))}
      (3 to 6) forall {i=>overflow.products.contains(hots(i))}
    }

    "when full, will first discard the most recent order" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      val storage = Storage(NoLogging, mutable.Map(Temperature.Hot->hot,Temperature.Cold->cold, Temperature.All->overflow))

      (0 to 6) foreach {a=>storage.putPackageOnShelf(hots(a),fixedTime)}
      assert(!hot.hasAvailableSpace)
      assert(!overflow.hasAvailableSpace)
      val result = storage.putPackageOnShelf(hots(7),fixedTime)
      assert(result.leftSide.nonEmpty)
      assert(result.leftSide.head.order == hots(3).order)
    }

    def print(shelf:Shelf): Unit = {
      println(s"${shelf.name} shelf capacity utilization:${shelf.products.size} / $shelf.capacity, decay rate modifier:$shelf.decayModifier.")
      println(s"       contents: ${shelf.products.map(product=>(product.order.name,product.value)).mkString(",")}")
    }

  }

}
