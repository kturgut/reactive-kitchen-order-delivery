package cloudkitchens.storage

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.testkit.TestProbe
import cloudkitchens.BaseSpec
import cloudkitchens.delivery.Courier.{DeliveryTimeWindowSizeInSeconds, EarliestDeliveryAfterOrderReceivedInSeconds}

class ExpirationInfoSpec extends BaseSpec {

  "A PackagedProduct" should {
    val customer = TestProbe(CustomerName)
    val product = samplePackagedProduct(1, customer.ref, 10, 0.5f, Hot, fixedTime)

    "be able to accurately calculate expiration time in millis at the time of package creation" in {
      val actual1 = product.expirationInMillis(1)
      assert(actual1 == 6750)
      val actual2 = product.expirationInMillis(2)
      assert(actual2 == 5050)
    }

    "be able to accurately calculate expiration time in millis after some time" in {
      val twoSecondsLater = product.phantomCopy(1, fixedTime.plus(2,ChronoUnit.SECONDS))
      val actual1 = twoSecondsLater.expirationInMillis(1)
      assert(actual1 == 4750)
      val actual2 = twoSecondsLater.expirationInMillis(2)
      assert(actual2 == 3550)
    }

    "be able to accurately calculate expected delivery window" in {
      val pickupWindow = product.pickupWindowInMillis(fixedTime)
      assert(pickupWindow == (EarliestDeliveryAfterOrderReceivedInSeconds * 1000 ,
        (DeliveryTimeWindowSizeInSeconds + EarliestDeliveryAfterOrderReceivedInSeconds) * 1000))
    }
  }

  "An ProductPair" should {
    val customer = TestProbe(CustomerName)
    val hot = Shelf.hot(3)
    val overflow = Shelf.overflow(4)
    val product1 = samplePackagedProduct(1, customer.ref, 2, 1f, Hot, fixedTime)
    val product2 = samplePackagedProduct(2, customer.ref, 4, 1f, Hot, fixedTime)
    val pair = ProductPair(product1,overflow,product2,hot)
    assert (pair.inOverflow.ifInCurrentShelf==750)
    assert (pair.inOverflow.ifMovedToTarget==1050)
    assert (pair.inTarget.ifInCurrentShelf==2050)
    assert (pair.inTarget.ifMovedToTarget==1400)

    "determine if a product should be discarded since it will expire for sure in given time" in {
      // will be saved if moved
      var expiringProduct = pair.willExpireForSure(650)
      assert(!expiringProduct.isDefined)

      // will expire after 1050
      expiringProduct = pair.willExpireForSure(1100)
      assert(expiringProduct.isDefined && expiringProduct.get._2 == product1)
    }
    "recommend swapping products between shelves if it will help improve total shelf life" in {
      assert(pair.swapRecommended(650))   // since it will improve shelf life
      assert(!pair.swapRecommended(1100)) // since it will expire regardless
      assert(!pair.swapRecommended(3000))
    }

  }




}