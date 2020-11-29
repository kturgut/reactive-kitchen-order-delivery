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
      assert(shelf.hasAvailableSpace && !shelf.isOverCapacity)

      shelf += product2
      assert(shelf.size == 1)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product2)

      shelf += product1
      assert(shelf.size == 2)
      assert(shelf.highestValueProduct == product2 && shelf.lowestValueProduct == product1)
      assert(shelf.hasAvailableSpace && !shelf.isOverCapacity)

      shelf += product3
      assert(shelf.size == 3)
      assert(shelf.highestValueProduct == product3 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasAvailableSpace && !shelf.isOverCapacity)

      shelf += product4
      assert(shelf.size == 4)
      assert(shelf.highestValueProduct == product4 && shelf.lowestValueProduct == product1)
      assert(!shelf.hasAvailableSpace && shelf.isOverCapacity)

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
    val later = time.plus(5000, ChronoUnit.MILLIS)
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
      assert(shelves.totalProductsOnShelves == 1)
      shelves.pickupPackageForOrder(shortLifeProduct.order,later) match {
        case Left(Some(_)) => fail("Should not have returned expired product")
        case Left(None) => fail("Should have found the product")
        case Right(discard: DiscardOrder) =>
          assert(discard.order == shortLifeProduct.order)
      }
    }

    "should not return the wrong product" in {
      shelves.putPackageOnShelf(shortLifeProduct)
      val later = time.plus(500, ChronoUnit.MILLIS)
      assert(shelves.totalProductsOnShelves == 1)
      assert(shortLifeProduct.order != longLifeProduct.order,later)
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

    "should discard the most recent orders if overflow capacity exceeded" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      var storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))
      val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 10) yield {
        samplePackagedProduct(i, customer.ref, i, 0.5f, Hot, time.plus(i * 10, ChronoUnit.MILLIS))
      }
      var discarded = List.empty[DiscardOrder]
      hots.foreach { product => discarded = discarded ++ storage.putPackageOnShelf(product, product.createdOn) ; report(storage,false)}
      assert(!overflow.hasAvailableSpace)
      assert(!overflow.isOverCapacity)
      assert(discarded.map(_.order) == (hots(7)::hots(8)::hots(9)::Nil).map(_.order))
    }

    "should discard most recent order if overflow capacity exceeded and none of the other products will expire for sure before delivery window" in {
      val hot = Shelf.hot(3)
      val overflow = Shelf.overflow(4)
      var storage = Storage(NoLogging, mutable.Map(Temperature.Hot -> hot, Temperature.All -> overflow))
      var later = time.plus(11, ChronoUnit.SECONDS)
      val hots: IndexedSeq[PackagedProduct] = for (i <- 0 until 10) yield {
        samplePackagedProduct(i, customer.ref, i + 100, 0.5f + i*0.01f, Hot, time.plus(-i*500, ChronoUnit.MILLIS))
      }
      hots.foreach(println)
      var discarded = List.empty[DiscardOrder]
      (0 to 6).map(hots(_)).foreach { product => discarded = discarded ++ storage.putPackageOnShelf(product, later)}
      val actualOverflowShelfData = (for (i<-7 until 10) yield {
        val product = hots(i);
        discarded = discarded ++ storage.putPackageOnShelf(product, later);
        report(storage,false)
        (discarded.last.order.id, collectShelfData(overflow))
      }).toMap
      val expectedOverflowShelfData = Map (
        "3" -> List ( ("4",76.96f,0.74f),("5",76.65f,0.73f),("6",76.32f,0.71999997f),("7",75.97f,0.71000004f)),
        "4" -> List ( ("5",76.65f,0.73f),("6",76.32f,0.71999997f),("7",75.97f,0.71000004f),("8",75.6f,0.7f)),
        "5" -> List (("6",76.32f,0.71999997f),("7",75.97f,0.71000004f),("8",75.6f,0.7f),("9",75.21f,0.69f))
      )
      assert(discarded.map(_.order) == (hots(3)::hots(4)::hots(5)::Nil).map(_.order))
      assert(!overflow.hasAvailableSpace)
      assert(!overflow.isOverCapacity)
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
