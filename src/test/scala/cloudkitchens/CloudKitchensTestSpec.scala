package cloudkitchens

import akka.actor.ActorRef
import cloudkitchens.delivery.Courier.CourierAssignment
import cloudkitchens.delivery.CourierDispatcher
import cloudkitchens.kitchen.PackagedProduct
import cloudkitchens.order.Order

class CloudKitchensTestSpec {

}


trait TestSpecHelper {

  val CourierName = "HardWorking"
  val OrderProcessorName = "OrderProcessor"
  val ShelfManagerName = "ShelfManager"
  val KitchenName = "Kitchen"




  def samplePackagedProductAndAssignment(id:Int, orderProcessor:ActorRef, courier:ActorRef):(PackagedProduct,CourierAssignment) = {
    val ayranOrder = Order(id.toString, "Ayran", "cold", 300, 0.5f, orderProcessor )
    val product = PackagedProduct(ayranOrder)
    val assignment = CourierAssignment(ayranOrder,CourierName, courier)
    (product,assignment)
  }

  def assertEquals(a:CourierAssignment, b:CourierAssignment):Unit = {
    assert(a.order == b.order)
    assert(a.courierRef == b.courierRef)
  }
}