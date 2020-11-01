package cloudkitchens.order

import akka.event.LoggingAdapter
import cloudkitchens.delivery.Courier.DeliveryComplete
import cloudkitchens.kitchen
import cloudkitchens.kitchen.ShelfManager.DiscardOrder
import spray.json.DefaultJsonProtocol.{FloatJsonFormat, IntJsonFormat, StringJsonFormat, jsonFormat5}
import spray.json.RootJsonFormat
import Temperature._


sealed trait Temperature {
  override def toString = this.getClass.getSimpleName
}
case object Temperature {
  case object Hot extends Temperature
  case object Cold extends Temperature
  case object Frozen extends Temperature
  case object All extends Temperature
  case object UnknownTemperature extends  Temperature
}

case class Order (id:String,
                  name:String,
                  temp:String, //temp:Temperature, TODO fix this during json unmarshal
                  shelfLife:Int,
                  decayRate: Float) {
  override def toString: String = s"Order (name:$name, temp:$temp, shelfLife:$shelfLife secs, decayRate:$decayRate, id: $id)"
  def temperature:Temperature =
    temp match {
      case "hot" => Hot
      case "cold" => Cold
      case "frozen" => Frozen
      case _ => UnknownTemperature
    }
}
case object Order {
  implicit val orderJsonFormat:RootJsonFormat[Order] = jsonFormat5(Order.apply)
}


case class OrderLifeCycle(order:Order,
                          product:Option[Product]=None,
                          delivery:Option[DeliveryComplete]=None,
                          discard:Option[DiscardOrder]=None) {
  def isComplete:Boolean = produced && (delivered || discarded)
  def produced: Boolean = product.isDefined
  def delivered:Boolean = delivery.isDefined
  def discarded:Boolean = discard.isDefined

  def update(productUpdate:kitchen.Product, log: LoggingAdapter): OrderLifeCycle = {
    if (product.isDefined) {
      log.warning(s"Product already created for order ${order.id}"); this
    }
    else this.copy(product = Some(productUpdate))
  }
  def update(deliveryUpdate:DeliveryComplete,  log: LoggingAdapter): OrderLifeCycle = {
    if (delivery.isDefined) {
      log.warning(s"Delivery already happened for order ${order.id}"); this
    }
    if (discard.isDefined) {
      log.error(s"This order ${order.id} is already delivered on ${delivery.get.time}, Ignoring discard notice"); this
    }
    else this.copy(delivery = Some(deliveryUpdate))
  }
  def update(discardUpdate:DiscardOrder,  log: LoggingAdapter): OrderLifeCycle = {
    if (discard.isDefined) {
      log.warning(s"This order already happened as discarded ${order.id}"); this
    }
    if (delivery.isDefined) {
      log.error(s"This order ${order.id} has been delivered on ${delivery.get.time}, Ignoring discard notice"); this
    }
    else this.copy(discard = Some(discardUpdate))
  }
}