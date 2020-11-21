package reactive.order

import java.time.LocalDateTime

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import reactive.delivery.Courier.DeliveryComplete
import reactive.order.Temperature._
import reactive.storage.PackagedProduct
import reactive.storage.ShelfManager.DiscardOrder
import spray.json.DefaultJsonProtocol.{FloatJsonFormat, IntJsonFormat, StringJsonFormat, jsonFormat5}
import spray.json.RootJsonFormat


case class OrderOnFile(id: String,
                       name: String,
                       temp: String,
                       shelfLife: Int,
                       decayRate: Float) {
  override def toString: String = s"Order (name:$name, temp:$temp, shelfLife:$shelfLife secs, decayRate:$decayRate, id:$id)"
}

case object OrderOnFile {
  implicit val orderJsonFormat: RootJsonFormat[OrderOnFile] = jsonFormat5(OrderOnFile.apply)
}

case class Order(id: String,
                 name: String,
                 temp: String, // TODO replace with Temperature with custom deserializer
                 shelfLife: Int,
                 decayRate: Float,
                 customer: ActorRef,
                 createdOn: LocalDateTime = LocalDateTime.now()
                ) {
  override def toString: String = s"Order (name:$name, temp:$temp, shelfLife:$shelfLife secs, decayRate:$decayRate, id:$id)"

  def temperature: Temperature = temp.toLowerCase() match {
    case "hot" => Hot
    case "cold" => Cold
    case "frozen" => Frozen
    case _ => UnknownTemperature
  }
}

sealed trait Temperature {
  override def toString = this.getClass.getSimpleName
}

case object Temperature {

  case object Hot extends Temperature
  case object Cold extends Temperature
  case object Frozen extends Temperature
  case object All extends Temperature
  case object UnknownTemperature extends Temperature

}


case object Order {

  def fromOrderOnFile(orderOnFile: OrderOnFile, customer: ActorRef): Order = {
    Order(orderOnFile.id, orderOnFile.name, orderOnFile.temp, orderOnFile.shelfLife, orderOnFile.decayRate, customer)
  }
}


case class OrderLifeCycle(order: Order,
                          product: Option[Product] = None,
                          delivery: Option[DeliveryComplete] = None,
                          discard: Option[DiscardOrder] = None) {

  def isComplete: Boolean = produced && (delivered || discarded)

  def produced: Boolean = product.isDefined

  def delivered: Boolean = delivery.isDefined

  def discarded: Boolean = discard.isDefined

  def update(productUpdate: PackagedProduct, log: LoggingAdapter): OrderLifeCycle = {
    if (product.isDefined) {
      log.warning(s"Product already created for order ${order.id}");
      this
    }
    else this.copy(product = Some(productUpdate))
  }

  def update(deliveryUpdate: DeliveryComplete, log: LoggingAdapter): OrderLifeCycle = {
    if (delivery.isDefined) {
      log.warning(s"Delivery already happened for order ${order.id}")
    }
    if (discard.isDefined) {
      log.error(s"This order ${order.id} is already delivered on ${delivery.get.time}, Ignoring discard notice");
      this
    }
    else this.copy(delivery = Some(deliveryUpdate))
  }

  def update(discardUpdate: DiscardOrder, log: LoggingAdapter): OrderLifeCycle = {
    if (discard.isDefined) {
      log.warning(s"This order already marked as discarded ${order.id}")
    }
    if (delivery.isDefined) {
      log.error(s"This order ${order.id} has been delivered on ${delivery.get.time}, Ignoring discard notice");
      this
    }
    else this.copy(discard = Some(discardUpdate))
  }
}