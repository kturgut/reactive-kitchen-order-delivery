package cloudkitchens

import java.time.LocalDateTime

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import cloudkitchens.delivery.Courier.CourierAssignment
import cloudkitchens.delivery.CourierDispatcher
import cloudkitchens.kitchen.PackagedProduct
import cloudkitchens.order.Order
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class CloudKitchensTestSpec {

}

class BaseSpec  extends TestKit (ActorSystem("TestActorSystem")) with ImplicitSender with TestSpecHelper
  with WordSpecLike
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}

trait TestSpecHelper {

  val CourierName = "HardWorking"
  val OrderProcessorName = "OrderProcessor"
  val ShelfManagerName = "ShelfManager"
  val KitchenName = "Kitchen"

  val RoundingErrorThreshold = 0.002f

  def samplePackagedProduct(id:Int, orderProcessor:ActorRef, shelfLife:Int = 100, decayRate:Float=0.5f, time:LocalDateTime=LocalDateTime.now()):PackagedProduct =
    PackagedProduct(Order(id.toString, "Ayran", "cold", shelfLife, decayRate, orderProcessor ), time)


  def samplePackagedProductAndAssignment(id:Int, orderProcessor:ActorRef, courier:ActorRef):(PackagedProduct,CourierAssignment) = {
    val product = samplePackagedProduct(id,orderProcessor)
    val assignment = CourierAssignment(product.order,CourierName, courier)
    (product,assignment)
  }

  def assertEquals(a:CourierAssignment, b:CourierAssignment):Unit = {
    assert(a.order == b.order)
    assert(a.courierRef == b.courierRef)
  }

  def assertEquals(a: List[(Float, Float)], b: List[(Float, Float)]): Boolean = {
    a.size == b.size &&
      (a zip b forall { ab => math.abs(ab._1._1 - ab._2._1) < RoundingErrorThreshold && math.abs(ab._1._2 - ab._2._2) < RoundingErrorThreshold
      })
  }

}