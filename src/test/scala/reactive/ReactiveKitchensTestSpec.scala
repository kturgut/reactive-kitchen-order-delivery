package reactive

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import reactive.delivery.Courier.CourierAssignment
import reactive.order.Order
import reactive.storage.PackagedProduct
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class ReactiveKitchensTestSpec {

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
  val OrderMonitorName = "OrderMonitor"
  val ShelfManagerName = "ShelfManager"
  val KitchenName = "Kitchen"
  val DispatcherName = "Dispatcher"
  val MonitorName = "Monitor"
  val Hot = "hot"
  val Cold = "cold"
  val Frozen = "frozen"
  val CustomerName = "Kagan"

  val RoundingErrorThreshold = 0.002f

  def samplePackagedProduct(id:Int, customer:ActorRef, shelfLife:Int = 100, decayRate:Float=0.5f, temp:String = Hot, time:LocalDateTime=LocalDateTime.now()):PackagedProduct =
    PackagedProduct(Order(id.toString, "Ayran", temp, shelfLife, decayRate, customer), (2000,6000), time)


  def samplePackagedProductAndAssignment(id:Int, customer:ActorRef, courier:ActorRef):(PackagedProduct,CourierAssignment) = {
    val product = samplePackagedProduct(id,customer)
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

  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  val fixedTime = LocalDateTime.parse("1970-03-09 06:00",formatter)


}