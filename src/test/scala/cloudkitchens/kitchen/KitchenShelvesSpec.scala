package cloudkitchens.kitchen

import java.time.LocalDateTime

import akka.event.NoLogging
import akka.testkit.TestProbe
import cloudkitchens.BaseSpec

import scala.collection.mutable

class KitchenShelvesSpec extends BaseSpec {


  "A Shelve" should {

    val orderProcessor = TestProbe(OrderProcessorName)
    val shelf  = Shelf.cold(3)
    val time = LocalDateTime.now()

    val product1 = samplePackagedProduct(1, orderProcessor.ref, 1000, 0.5f, time)
    val product2 = samplePackagedProduct(2, orderProcessor.ref, 2000, 0.5f, time)
    val product3 = samplePackagedProduct(3, orderProcessor.ref, 3000, 0.5f, time)
    val product4 = samplePackagedProduct(4, orderProcessor.ref, 3000, 0.5f, time)

    "store a single product and give it back if asked before it expires" in {
      assert(shelf.size == 0)
      shelf += product1
      assert(shelf.size == 1)
      shelf += product1
      assert(shelf.size == 1)
      shelf -= product1
      assert(shelf.size == 0)
    }

    "store multiple products and preserve priority order" in {
      assert(shelf.size == 0)
      assert(shelf.hasRoom && !shelf.overCapacity)

      shelf += product2
      assert(shelf.size == 1)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product2)

      shelf += product1
      assert(shelf.size == 2)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product1)
      assert(shelf.hasRoom && !shelf.overCapacity)

      shelf += product3
      assert(shelf.size == 3)
      assert(shelf.highestValueProduct == product3 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasRoom && !shelf.overCapacity)

      shelf += product4
      assert(shelf.size == 4)
      assert(shelf.highestValueProduct == product4 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasRoom && shelf.overCapacity)

      shelf -= product2
      shelf -= product4
      assert(shelf.size == 2)
      assert(shelf.highestValueProduct == product3 && shelf.lowestValueProduct == product1)
    }

    "support finding a packaged product by order" in {
      assert(!shelf.getPackageForOrder(product1.order).isDefined)
      shelf += product1
      var response = shelf.getPackageForOrder(product1.order)
      assert(response.isDefined && response.get.order == product1.order)
      shelf += product2
      response = shelf.getPackageForOrder(product1.order)
      assert(response.isDefined && response.get.order == product1.order)
      shelf += product3
      shelf += product4
      response = shelf.getPackageForOrder(product4.order)
      assert(response.isDefined && response.get.order == product4.order)
    }
  }



  "A KitchenShelves" should {

    val orderProcessor = TestProbe(OrderProcessorName)
    val shelves  = KitchenShelves(NoLogging, Shelf.temperatureSensitiveShelves)
    val time = LocalDateTime.now()

    "store a single product and give it back if asked before it expires" in {
      val product = samplePackagedProduct(1, orderProcessor.ref, 1000)
      assert(shelves.totalProductsOnShelves == 0)
      shelves.putPackageOnShelf(product)
      assert(shelves.totalProductsOnShelves == 1)
      val received = shelves.fetchPackageForOrder(product.order)
      assert (received.isDefined && received.get.order == product.order)
      assert(shelves.totalProductsOnShelves == 0)
    }
  }

}
