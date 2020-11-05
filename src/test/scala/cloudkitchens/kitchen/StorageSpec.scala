package cloudkitchens.kitchen

import java.time.LocalDateTime

import akka.event.NoLogging
import akka.testkit.TestProbe
import cloudkitchens.BaseSpec
import cloudkitchens.kitchen.ShelfManager.DiscardOrder

class StorageSpec extends BaseSpec {


  "A Shelve" should {

    val orderProcessor = TestProbe(OrderProcessorName)
    val time = LocalDateTime.now()

    val product1 = samplePackagedProduct(1, orderProcessor.ref, 1000, 0.5f, time)
    val product2 = samplePackagedProduct(2, orderProcessor.ref, 2000, 0.5f, time)
    val product3 = samplePackagedProduct(3, orderProcessor.ref, 3000, 0.5f, time)
    val product4 = samplePackagedProduct(4, orderProcessor.ref, 3000, 0.5f, time)

    "store a single product and give it back if asked before it expires" in {
      val shelf = Shelf.cold(3)
      assert(shelf.size == 0)
      shelf += product1
      assert(shelf.size == 1)
      shelf += product1
      assert(shelf.size == 1)
      shelf -= product1
      assert(shelf.size == 0)
    }

    "store multiple products and preserve priority order" in {
      val shelf = Shelf.cold(3)
      assert(shelf.size == 0)
      assert(shelf.hasAvailableSpace && !shelf.overCapacity)

      shelf += product2
      assert(shelf.size == 1)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product2)

      shelf += product1
      assert(shelf.size == 2)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product1)
      assert(shelf.hasAvailableSpace && !shelf.overCapacity)

      shelf += product3
      assert(shelf.size == 3)
      assert(shelf.highestValueProduct == product3 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasAvailableSpace && !shelf.overCapacity)

      shelf += product4
      assert(shelf.size == 4)
      assert(shelf.highestValueProduct == product4 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasAvailableSpace && shelf.overCapacity)

      shelf -= product2
      shelf -= product4
      assert(shelf.size == 2)
      assert(shelf.highestValueProduct == product3 && shelf.lowestValueProduct == product1)
    }

    "support finding a packaged product by order" in {
      val shelf = Shelf.cold(3)
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

  "A Storage" should {

    val orderProcessor = TestProbe(OrderProcessorName)
    val shelves = Storage(NoLogging, Shelf.temperatureSensitiveShelves)
    val time = LocalDateTime.now()
    val shortLifeProduct = samplePackagedProduct(1, orderProcessor.ref, 1, 0.99f, time)
    val longLifeProduct = samplePackagedProduct(2, orderProcessor.ref, 1000, 0.5f, time)

    "store a single product and get it back" in {
      assert(shelves.totalProductsOnShelves == 0)
      shelves.putPackageOnShelf(longLifeProduct)
      assert(shelves.totalProductsOnShelves == 1)
      val received = shelves.fetchPackageForOrder(longLifeProduct.order)
      assert(received.isDefined && received.get.order == longLifeProduct.order)
      assert(shelves.totalProductsOnShelves == 0)
    }

    "store a single product and get it back before it expires" in {
      assert(shelves.totalProductsOnShelves == 0)
      shelves.putPackageOnShelf(longLifeProduct)
      assert(shelves.totalProductsOnShelves == 1)
      shelves.pickupPackageForOrder(longLifeProduct.order) match {
        case Left(Some(product)) =>
          assert(product != longLifeProduct)
          assert(product.createdOn == longLifeProduct.createdOn)
          assert(product.value < longLifeProduct.value)
          assert(product.remainingShelfLife < longLifeProduct.remainingShelfLife)
        case Left(None) => fail("Should have found the product")
        case Right(discard: DiscardOrder) => fail("Product should not have expired")
      }
    }

    "store a single product and give return a DiscardOrder if it expired before pickup" in {
      shelves.putPackageOnShelf(shortLifeProduct)
      Thread.sleep(500)
      assert(shelves.totalProductsOnShelves == 1)
      shelves.pickupPackageForOrder(shortLifeProduct.order) match {
        case Left(Some(_)) => fail("Should not have returned expired product")
        case Left(None) => fail("Should have found the product")
        case Right(discard: DiscardOrder) =>
          assert(discard.order == shortLifeProduct.order)
      }
    }

    "should not return the wrong product" in {
        shelves.putPackageOnShelf(shortLifeProduct)
        Thread.sleep(500)
        assert(shelves.totalProductsOnShelves == 1)
        assert(shortLifeProduct.order != longLifeProduct.order)
        shelves.pickupPackageForOrder(longLifeProduct.order) match {
          case Left(Some(_)) => fail("Should not have found the product")
          case Left(None) => "Test Passed!"
          case Right(_: DiscardOrder) => fail("Wrong expired product ")
        }
      }
    }
}
