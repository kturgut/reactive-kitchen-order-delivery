package cloudkitchens.order

import java.time.LocalDateTime

import akka.actor.{ActorIdentity, ActorLogging, ActorRef, Identify, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import cloudkitchens.CloudKitchens.KitchenActorName
import cloudkitchens.customer.Customer
import cloudkitchens.delivery.Courier.DeliveryComplete
import cloudkitchens.delivery.CourierDispatcher
import cloudkitchens.kitchen.{Kitchen, PackagedProduct}
import cloudkitchens.{JacksonSerializable, kitchen, system}
import cloudkitchens.kitchen.Kitchen.KitchenReadyForService
import cloudkitchens.kitchen.ShelfManager.DiscardOrder

import scala.collection.immutable.ListMap


case object OrderProcessor {

  case class OrderReceived(id:String)
  case object OrderReceivedAck

  // EVENTS
  case class OrderRecord(time:LocalDateTime, order:Order)  extends JacksonSerializable
  case class ProductRecord(time:LocalDateTime, product:kitchen.PackagedProduct)  extends JacksonSerializable
  case class DeliveryCompleteRecord(time:LocalDateTime, delivery:DeliveryComplete)  extends JacksonSerializable
  case class DiscardOrderRecord(time:LocalDateTime, discard:DiscardOrder)  extends JacksonSerializable
  case class KitchenRelationshipRecord(name:String, actorPath:String) extends JacksonSerializable

  val MaximumSizeForLifeCycleCache = 200
}


class OrderProcessor extends PersistentActor with ActorLogging {
  import OrderProcessor._

  var orderCounter = 0
  var lastOrderReceived = "Unknown"
  var deliveryCounter = 0
  var discardedOrderCounter = 0
  var totalTipsReceived = 0

  override val persistenceId:String = "persistentOrderProcessorId"

  var kitchens: Map[String,ActorRef] = Map.empty
  var activeOrders:ListMap[String,OrderLifeCycle] = ListMap.empty

  // normal command handler
  override def receiveCommand() : Receive = {

    case KitchenReadyForService(name,_, kitchenRef, _, _) =>
      val event = KitchenRelationshipRecord(name,kitchenRef.path.toString)
      persist(event) { eventRecorded =>
        log.info(s"A new kitchen named:${eventRecorded.name} is registered to receive orders at:${kitchenRef.path}.")
        kitchens += (eventRecorded.name -> kitchenRef)
        unstashAll()
      }
    case order:Order =>
      if (kitchens.isEmpty)
        stash()
      else {
        val event = OrderRecord(LocalDateTime.now(),order)
        persist(event) { eventRecorded=>
          orderCounter +=1
          lastOrderReceived = eventRecorded.order.name
          sender() ! OrderReceived(order.id)
          log.info(s"Received ${orderCounter}th order on ${event.time}. Sending it kitchen:$order")
          selectKitchenForOrder(kitchens, order) ! order
          updateState(order, (lifeCycle:OrderLifeCycle)=>lifeCycle, ()=>OrderLifeCycle(order))
        }
      }
    case product:PackagedProduct =>
        val event =  ProductRecord(LocalDateTime.now(),product)
        persist(event) { event=>
          log.debug(s"Order update: produced: ${event.product}")
          updateState(event.product.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(event.product,log),
            ()=>OrderLifeCycle(event.product.order,Some(event.product)))
        }
    case discard:DiscardOrder =>
      val event =  DiscardOrderRecord(LocalDateTime.now(),discard)
      persist(event) { event=>
        discardedOrderCounter += 1
        log.debug(s"Order update: discarded: ${event.discard.order.name} with id ${event.discard.order.id}. Total discarded:$discardedOrderCounter")
        updateState(event.discard.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(event.discard,log),
          ()=>OrderLifeCycle(event.discard.order,Some(event.discard.order)))
      }
    case delivery:DeliveryComplete =>
      val event =  DeliveryCompleteRecord(delivery.time,delivery)
      persist(event) { event=>
        val tip = event.delivery.acceptance.tips
        totalTipsReceived += tip
        deliveryCounter += 1
        log.debug(s"Order update: delivered: ${event.delivery.prettyString()}")
        updateState(event.delivery.assignment.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(event.delivery,log),
          ()=>OrderLifeCycle(event.delivery.assignment.order,Some(event.delivery.assignment)))
      }
    case ActorIdentity(name, Some(actorRef)) =>
      log.error(s"OrderProcessor re-establish connection with kitchen named $name")
      kitchens  = kitchens + (name.toString -> actorRef)
    case ActorIdentity(name, None) =>
      log.error(s"OrderProcessor could not re-establish connection with kitchen named $name. THIS SHOULD NOT HAPPEN!")
       kitchens  = kitchens - name.toString //TODO this should not happen.

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  // This method is called on recovery e.g. in case system needs restart OrderHandler after a crash.
  // the restart would be handled by the parent actor as part of Supervision strategy.
  // This would give us opportunity to preserve state as, until the restart happens all incoming messages
  // would be queued in the mailbox of the OrderProcessor. Thus for example we can reissue an order to kitchen
  // if we had received the order but did not get the record that shows that kitchen has produced the Product yet!
  override def receiveRecover():Receive = {

    case RecoveryCompleted =>
      if (orderCounter>0) {
        reportStateAfterRecovery()
        reissueOrdersNotProcessed()
      }

    case KitchenRelationshipRecord(name,actorPath) =>
        log.info(s"Recovering relationship with kitchen named ${name} servicing at:${actorPath}.")
        context.actorSelection(actorPath) ! Identify(name)

    case OrderRecord(date,order) =>
      orderCounter += 1
      lastOrderReceived = order.name
      log.info(s"Recovering  $order received on: $date  ")
      updateState(order, (lifeCycle:OrderLifeCycle)=>lifeCycle, ()=>OrderLifeCycle(order))

    case ProductRecord(date,product) =>
      log.info(s"Recovering  $product received on: $date  ")
      updateState(product.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(product,log),
        ()=>OrderLifeCycle(product.order,Some(product)))

    case DiscardOrderRecord(date,discard) =>
      log.info(s"Recovering  $discard received on: $date  ")
      updateState(discard.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(discard,log),
          ()=>OrderLifeCycle(discard.order,Some(discard.order)))

    case DeliveryCompleteRecord(time,deliveryComplete) =>
      log.info(s"Recovering  $deliveryComplete")
      val tip = deliveryComplete.acceptance.tips
      totalTipsReceived += tip
      deliveryCounter += 1
      log.debug(s"Order update: delivered: ${deliveryComplete.prettyString()}")
      updateState(deliveryComplete.assignment.order, (lifeCycle:OrderLifeCycle)=>lifeCycle.update(deliveryComplete,log),
        ()=>OrderLifeCycle(deliveryComplete.assignment.order,Some(deliveryComplete.assignment)))


  }

  /**
  Create or update lifecycle entry for this order. Removed closed orders and keep cache size under max size
   */
  def updateState(order:Order,
                  update:(OrderLifeCycle)=>OrderLifeCycle,
                  create:()=>OrderLifeCycle
                 ):Unit =
    activeOrders = (activeOrders.get(order.id) match {
      case Some(lifeCycle) =>
        val updatedEntry = update(lifeCycle)
        if (updatedEntry.isComplete) activeOrders else activeOrders + (order.id->updatedEntry)
      case None => activeOrders + (order.id -> create())
    }).take(MaximumSizeForLifeCycleCache)

  // TODO this where we would normally match orders to kitchens if multiple kitchens were registered
  def selectKitchenForOrder(kitchens: Map[String, ActorRef], order: Order):ActorRef = {
    assert(kitchens.nonEmpty)
    kitchens.values.head
  }

  def reportStateAfterRecovery() = {
    log.info("OrderProcessor completed recovery of state upon restart")
    log.info(s"  Total orders received:$orderCounter.")
    log.info(s"  Total tips received:$orderCounter.")
    log.info(s"  Total active orders:${activeOrders.size}")
    log.info(s"  Orders pending production: ${activeOrders.values.count(!_.produced)}.")
    log.info(s"  Orders pending delivery: ${activeOrders.values.count(_.isComplete)}.")
    log.info(s"  Total orders delivered:$deliveryCounter.")
    log.info(s"  Total orders discarded:$discardedOrderCounter.")
  }

  def reissueOrdersNotProcessed() = {
    activeOrders.values.filter(!_.produced).map(_.order).foreach(order=>selectKitchenForOrder(kitchens, order) ! order)
    // TODO  request update for orders on delivery
  }
}

case object OrderHandlerManualTest extends App {
  val orderHandler = cloudkitchens.system.actorOf(Props[OrderProcessor],"orderHandler")
  val dispatcher = cloudkitchens.system.actorOf(Props[CourierDispatcher],"dispatcher")
  val kitchen = system.actorOf(Kitchen.props(Kitchen.TurkishCousine,2),s"${KitchenActorName}_${Kitchen.TurkishCousine}")
  kitchen ! Kitchen.InitializeKitchen(orderHandler,dispatcher)

  val customer = cloudkitchens.system.actorOf(Props[Customer])

  for (i<-1 to 100) {
    orderHandler ! Order(i.toString, s" yummy_food_$i", "hot", i*10, 1f/i, customer)
  }
}
