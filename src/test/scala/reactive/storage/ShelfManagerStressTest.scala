package reactive.storage

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.event.NoLogging
import akka.testkit.TestProbe
import reactive.BaseSpec
import reactive.order.Temperature
import reactive.storage.ShelfManager.DiscardOrder

import scala.collection.mutable

class ShelfManagerStressTest  extends BaseSpec with StorageHelper {


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
