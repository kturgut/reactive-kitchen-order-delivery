package cloudkitchens.order

import java.time.LocalDateTime

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import cloudkitchens.kitchen
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService

import scala.collection.immutable.ListMap



case object OrderProcessor {

  case class OrderReceived(id:String)
  case object OrderReceivedAck

  // EVENTS
  case class OrderRecorded(time:LocalDateTime, order:Order)
  case class ProductRecorded(time:LocalDateTime, product:kitchen.Product)

  val MaximumSizeForLifeCycleCache = 200
}

class OrderProcessor extends PersistentActor with ActorLogging {
  import OrderProcessor._

  var orderCounter = 0
  var lastOrderReceived = "Unknown"

  override val persistenceId:String = "persistentOrderProcessorId"

  override val receiveCommand: Receive = receiveCommandsTrackingOrderLifecycle(Map(),ListMap())
  override def receiveRecover:Receive = receiveRecoverEventsTrackingOrderLifeCycle(ListMap())

  /**
   Create or update lifecycle entry for this order. Removed closed orders and keep cache size under max size
   */
  def updateCache(activeOrders:ListMap[String,OrderLifeCycle],
                  order:Order,
                  update:(OrderLifeCycle)=>OrderLifeCycle,
                  create:()=>OrderLifeCycle
                                ):ListMap[String,OrderLifeCycle] =
    (activeOrders.get(order.id) match {
      case Some(lifeCycle) =>
        val updatedEntry = update(lifeCycle)
        if (updatedEntry.isComplete) activeOrders else activeOrders + (order.id->updatedEntry)
      case None => activeOrders + (order.id -> create())
    }).take(MaximumSizeForLifeCycleCache)

  // TODO this where we would match orders to kitchens
  def selectKitchenForOrder(kitchens: Map[String, ActorRef], order: Order) = {
    assert(kitchens.nonEmpty)
    kitchens.values.head
  }

  // normal command handler
  def receiveCommandsTrackingOrderLifecycle(kitchens: Map[String,ActorRef],
                                            activeOrders:ListMap[String,OrderLifeCycle]) : Receive = {
    case KitchenReadyForService(name,actorRef) =>
      log.info(s"A new kitchen named:$name is registered to receive orders.")
      if (kitchens.isEmpty) unstashAll()
      context.become(receiveCommandsTrackingOrderLifecycle(kitchens + (name -> actorRef), activeOrders))

    case order:Order =>
      if (kitchens.isEmpty) stash()
      else {
        val event = OrderRecorded(LocalDateTime.now(),order)
        log.info(s"Received $order on ${event.time}")
        persist(event) { eventRecorded=>
          orderCounter +=1
          lastOrderReceived = eventRecorded.order.name
          log.debug(s"Persisted $orderCounter order records. Last order received was for $lastOrderReceived")
          sender() ! OrderReceived(order.id)
          log.info(s"Sending order to kitchen now $order")
          selectKitchenForOrder(kitchens, order) ! order
          val updatedCache = updateCache(activeOrders, order, (lifeCycle:OrderLifeCycle)=>lifeCycle, ()=>OrderLifeCycle(order))
          context.become(receiveCommandsTrackingOrderLifecycle(kitchens,updatedCache))
        }
      }
    case product:kitchen.Product =>
        val event =  ProductRecorded(LocalDateTime.now(),product)
        log.info(s"Received $product on ${event.time}")
        persist(event) { eventRecorded=>
          log.debug(s"Persisted product creation record: $product")
          val updatedCache = updateCache(activeOrders, product.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(product,log),
            ()=>OrderLifeCycle(product.order,Some(product)))
          context.become(receiveCommandsTrackingOrderLifecycle(kitchens,updatedCache))
        }
    case other => log.warning(s"Received unrecognized message $other")
  }

  // will be called on recovery.. in case we need to restart OrderHandler after a crash
  def receiveRecoverEventsTrackingOrderLifeCycle(activeOrders:ListMap[String,OrderLifeCycle]):Receive = {

    case RecoveryCompleted =>
      log.debug("OrderProcessor completed recovery of state upon restart. It will reprocess orders active state")
      activeOrders.values.filter(!_.product.isDefined)


    case OrderRecorded(date,order) =>
      orderCounter += 1
      lastOrderReceived = order.name
      log.info(s"Recovering  $order received on: $date  ")
      val updatedCache = updateCache(activeOrders, order, (lifeCycle:OrderLifeCycle)=>lifeCycle, ()=>OrderLifeCycle(order))
      context.become(receiveRecoverEventsTrackingOrderLifeCycle(updatedCache))

    case ProductRecorded(date,product) =>
      log.info(s"Recovering  $product received on: $date  ")
      val updatedCache = updateCache(activeOrders, product.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(product,log),
        ()=>OrderLifeCycle(product.order,Some(product)))
      context.become(receiveRecoverEventsTrackingOrderLifeCycle(updatedCache))

  }
}

case object OrderHandlerTest extends App {
  val orderHandler = cloudkitchens.system.actorOf(Props[OrderProcessor],"orderHandler")
  for (i<-1 to 100) {
    orderHandler ! Order(i.toString, s" yummy_food_$i", "hot", i*10, 1f/i)
  }
}

