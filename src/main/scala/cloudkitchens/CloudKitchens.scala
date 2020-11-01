package cloudkitchens

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props, Stash, Terminated}
import cloudkitchens.delivery.CourierDispatcher
import cloudkitchens.kitchen.Kitchen
import cloudkitchens.order.OrderPipeline.SimulateOrdersFromFile
import cloudkitchens.order.{OrderProcessor, OrderPipeline}

import scala.concurrent.duration.DurationInt

object CloudKitchens {

  val CloudKitchensActorSystemName = "CK_ActorSystem"
  val KitchenActorName = "Kitchen"
  val ShelfManagerActorName = "ShelfManager"
  val OrderProcessorActorName = "OrderProcessor"
  val CourierDispatcherActorName = "CourierDispatcher"
  val OrderStreamSimulatorActorName = "OrderStreamSimulator"
  val CloudKitchensActorName = "CloudKitchens"
  val componentNames = Seq(OrderProcessorActorName,KitchenActorName,CourierDispatcherActorName, OrderStreamSimulatorActorName)

  val MaxNumberOfOrdersPerSecond = 2 // TODO read this from config
  def numberOfCouriersNeeded = MaxNumberOfOrdersPerSecond * 10 // TODO read from config

  case class StartComponent(name:String)
  case class ComponentStarted(name:String,ref:ActorRef)
  case class StopComponent(name:String)

  case object Initialize
  case object Shutdown
  case object GracefulShutdown
  case object RunSimulation
}


class CloudKitchens extends Actor with ActorLogging with Stash {
  import CloudKitchens._
  override def receive:Receive = closedForService(Map())

  import system.dispatcher

  def closedForService(components:Map[String,ActorRef]):Receive = {
    case Initialize =>
      log.info(s"Initializing $CloudKitchensActorName")
      self !  StartComponent(OrderProcessorActorName)
      self ! StartComponent(CourierDispatcherActorName)
      self ! StartComponent(OrderStreamSimulatorActorName)
      self ! StartComponent(KitchenActorName)

    case StartComponent(name) => startComponent(name, components)

    case Shutdown =>
      log.info("Shutting down CloudKitchens!")
      context.stop(self)

    case _ =>
      log.info(s"Stashing messages until initialization is complete")
      stash()
  }

  def openForService(components:Map[String,ActorRef], timeoutSchedule:Option[Cancellable] = None): Receive = {

    case StartComponent(name) => startComponent(name, components)

    case Shutdown =>
      log.info("Gracefully Shutting down CloudKitchens!")
      componentNames.filter(components.contains(_)).foreach { components(_) ! PoisonPill }

    case StopComponent(name) =>
      log.info(s"Stopping component $name")
      components.get(name).foreach(componentRef => context.stop(componentRef))

    case Terminated(ref) =>
      log.info (s"Component with ref ${ref.path} is terminated")

    case RunSimulation =>
      system.scheduler.scheduleOnce(100 milliseconds) {
        log.info(s"Starting Order Simulation with components: [${components.keys.mkString(",")}]")
        components.get(OrderProcessorActorName) match {
          case Some(orderHandler) => components.get(OrderStreamSimulatorActorName) match {
            case Some(simulator) => simulator ! SimulateOrdersFromFile(orderHandler,MaxNumberOfOrdersPerSecond)
          }}
      }
  }

  def createTimeoutWindow():Cancellable = {
    context.system.scheduler.scheduleOnce(2 seconds) {
      self ! Shutdown
    }
  }

  def checkInitializationComplete():Cancellable = { // TODO shutdown when no activity
    context.system.scheduler.scheduleOnce(2 seconds) {
      self ! Shutdown
    }
  }

  def startComponent(name:String, components: Map[String,ActorRef]): Unit = {
    assert(componentNames.contains(name), s"Unknown component name:$name")
    val child = name match {
      case OrderStreamSimulatorActorName => context.actorOf(Props[OrderPipeline],OrderStreamSimulatorActorName)
      case OrderProcessorActorName => context.actorOf(Props[OrderProcessor],OrderProcessorActorName)
      case KitchenActorName =>
        val kitchen = context.actorOf(Kitchen.props(Kitchen.TurkishCousine),s"${KitchenActorName}_${Kitchen.TurkishCousine}")
        kitchen ! Kitchen.Initialize(self.path/CourierDispatcherActorName, self.path/OrderProcessorActorName)
        kitchen
      case CourierDispatcherActorName =>
        val courierDispatcher = context.actorOf(Props[CourierDispatcher],CourierDispatcherActorName)
        courierDispatcher ! CourierDispatcher.Initialize (numberOfCouriersNeeded, self.path/OrderProcessorActorName)
        courierDispatcher
    }
    log.info(s"Starting component $name at path ${child.path}")
    context.watch(child)
    sender() ! ComponentStarted(name,child)
    val withNewComponent = components + (name -> child)
    if (componentNames.forall(withNewComponent.contains(_))) {
      log.info("Initialization is complete. Replaying all queued up messages.")
      unstashAll()
      context.become(openForService(withNewComponent))
    } else
      context.become(closedForService(withNewComponent))
  }
}

object CloudKitchenManualTest extends App {
  import CloudKitchens._
  val simulation = system.actorOf(Props[CloudKitchens],CloudKitchensActorName)
  simulation ! Initialize
  simulation ! RunSimulation
}
