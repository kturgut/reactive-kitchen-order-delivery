package reactive.storage

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.event.NoLogging
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import reactive.BaseSpec
import reactive.config.ShelfConfig
import reactive.order.Temperature
import reactive.storage.PackagedProduct.dateFormatter
import reactive.storage.ShelfManager.DiscardOrder

import scala.collection.mutable

class StorageSpec extends BaseSpec with StorageHelper {


  "A Shelve" should {

    val customer = TestProbe(CustomerName)
    val time = LocalDateTime.now()

    val product1 = samplePackagedProduct(1, customer.ref, 1000, 0.5f, Cold, time)
    val product2 = samplePackagedProduct(2, customer.ref, 2000, 0.5f, Cold, time)
    val product3 = samplePackagedProduct(3, customer.ref, 3000, 0.5f, Cold, time)
    val product4 = samplePackagedProduct(4, customer.ref, 3000, 0.5f, Cold, time)

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

    val customer = TestProbe(CustomerName)
    val shelves = Storage(NoLogging, Shelf.temperatureSensitiveShelves(shelfConfig))
    val time = LocalDateTime.now()
    val shortLifeProduct = samplePackagedProduct(1, customer.ref, 1, 0.99f, Hot, time)
    val longLifeProduct = samplePackagedProduct(2, customer.ref, 1000, 0.5f, Hot, time)

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

    "should be able to age products on its shelves over time.. without optimization kicking in" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      var storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))
      val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 5) yield {
        samplePackagedProduct(i, customer.ref, i, 0.5f, Hot, time)
      }
      hots.foreach(product => storage.putPackageOnShelf(product, product.createdOn))

      val actual = (for (timeIncrement <- 0 to 1000 by 500) yield {
        storage = storage.snapshot(time.plus(timeIncrement, ChronoUnit.MILLIS))
        report(storage)
        timeIncrement -> collectStorageData(storage)
      }).toMap
      val expected = {
        Map(
          0 -> Map(Temperature.Hot -> List(("0", 0.0f, 1.0f), ("1", 1.0f, 1.0f), ("2", 2.0f, 1.0f)), Temperature.All -> List(("3", 3.0f, 1.0f), ("4", 4.0f, 1.0f))),
          500 -> Map(Temperature.Hot -> List(("0", 0.0f, 0.0f), ("1", 0.25f, 0.25f), ("2", 1.25f, 0.625f)), Temperature.All -> List(("3", 2.0f, 0.6666667f), ("4", 3.0f, 0.75f))),
          1000 -> Map(Temperature.Hot -> List(("0", 0.0f, 0.0f), ("1", 0.0f, 0.0f), ("2", 0.5f, 0.25f)), Temperature.All -> List(("3", 1.0f, 0.33333334f), ("4", 2.0f, 0.5f)))
        )
      }
      assert(actual == expected)
    }

    "should not overflow" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      var storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))
      val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 10) yield {
        samplePackagedProduct(i, customer.ref, i, 0.5f, Hot, time.plus(i * 10, ChronoUnit.MILLIS))
      }
      hots.foreach { product => storage.putPackageOnShelf(product, product.createdOn) }
      assert(!overflow.hasAvailableSpace)
      assert(!overflow.overCapacity)
    }


  }
}

trait StorageHelper {

  def collectStorageData(storage: Storage): Map[Temperature, List[(String, Float, Float)]] =
    storage.shelves.map(shelf => shelf._1 -> collectShelfData(shelf._2)).toMap

  def collectShelfData(shelf: Shelf): List[(String, Float, Float)] =
    shelf.products.map(p => (p.order.id, p.remainingShelfLife, p.value)).toList

  def report(storage: Storage, verbose: Boolean = false): Unit = {
    def print(shelf: Shelf): Unit = {
      println(s"${shelf.name} shelf capacity utilization:${shelf.products.size} / ${shelf.capacity}, decay rate modifier:${shelf.decayModifier} createdOn: ${storage.createdOn.format(dateFormatter)}")
      if (verbose)
        println(s"       contents: ${shelf.products.map(product => (product.order.id, product.order.shelfLife, product.order.name, product.value, product.createdOn.format(dateFormatter))).mkString(",")}")
      else
        println(s"       contents: ${shelf.products.map(product => (product.order.id, product.remainingShelfLife, product.value)).mkString(",")}")
    }

    println
    storage.shelves.values.foreach(print)
  }

  def shelfConfig = {
    val configString =
    """
      |      hot-shelf-capacity = 10
      |      cold-shelf-capacity = 10
      |      frozen-shelf-capacity = 10
      |      overflow-shelf-capacity = 15
      |
      |      hot-shelf-decay-modifier = 1
      |      cold-shelf-decay-modifier = 1
      |      frozen-shelf-decay-modifier = 1
      |      overflow-shelf-decay-modifier = 2
      |""".stripMargin
    val config = ConfigFactory.parseString(configString)
    ShelfConfig(config)
  }
}
