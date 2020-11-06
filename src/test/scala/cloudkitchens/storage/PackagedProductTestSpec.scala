package cloudkitchens.storage

import java.time.LocalDateTime

import akka.testkit.TestProbe
import cloudkitchens.BaseSpec

class PackagedProductTestSpec extends BaseSpec {

   "A PackagedProduct" should {
     val orderProcessor = TestProbe(OrderProcessorName)
     val time = LocalDateTime.now()
     var product = samplePackagedProduct(1, orderProcessor.ref, 10, 0.5f)

     "be able to create a copy of itself in future time maintaining original creation time" in {
       val timeInFuture = time.plusSeconds(2)
       val phantom = product.phantomCopy(2, timeInFuture)
       assert(phantom.createdOn == phantom.createdOn)
       assert(phantom.updatedOn == timeInFuture)
     }

     "value should deprecate over time based on decayRate and shelf decayModifier" in {
       val actual = for (secondsIntoFuture <- 0 until 10)
         yield {
           val copy = product.phantomCopy(1, time.plusSeconds(secondsIntoFuture))
           (copy.remainingShelfLife, copy.value)
         }
       assertEquals(actual.toList, expected)
     }

     "value should deprecate over time based on decayRate and shelf decayModifier with recurrent phantoms" in {
       val actual = for (secondsIntoFuture <- 0 until 10) yield {
         product = product.phantomCopy(1, time.plusSeconds(secondsIntoFuture))
         (product.remainingShelfLife, product.value)
       }
       assertEquals(actual.toList, expected)
     }

     "compute value properly if shelf life is not positive" in {
       val expiredProduct = samplePackagedProduct(1, orderProcessor.ref, 0, 0.5f)
       val actual1 = for (secondsIntoFuture <- 0 until 5) yield expiredProduct.phantomCopy(1, time.plusSeconds(secondsIntoFuture)).value
       val expected = List(0f, 0f, 0f, 0f, 0f)
       assert(actual1 == expected)

       val invalidProduct = samplePackagedProduct(1, orderProcessor.ref, -1, 0.5f)
       val actual2 = for (secondsIntoFuture <- 0 until 5) yield invalidProduct.phantomCopy(1, time.plusSeconds(secondsIntoFuture)).value
       assert(actual2 == expected)
     }



   }
  val expected = List((10f, 1f), (8.5f, 0.85f), (7f, 0.7f), (5.5f, 0.55f), (4f, 0.4f), (2.5f, 0.25f), (1f, 0.1f), (0f, 0f), (0f, 0f), (0f, 0f))

}
