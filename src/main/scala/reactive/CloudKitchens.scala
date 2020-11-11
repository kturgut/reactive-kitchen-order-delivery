package reactive

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, OneForOneStrategy, PoisonPill, Props, Stash, Terminated}
import reactive.customer.Customer
import reactive.customer.Customer.SimulateOrdersFromFile
import reactive.delivery.CourierDispatcher
import reactive.kitchen.Kitchen
import reactive.order.OrderProcessor

import scala.concurrent.duration.DurationInt

trait JacksonSerializable

sealed trait ComponentStatus

case object NotReadyForService extends ComponentStatus

case object Initializing extends ComponentStatus

case object ReadyForService extends ComponentStatus

case object ShuttingDown extends ComponentStatus


object CloudKitchens {

  val ReactiveKitchensActorSystemName = "CK_ActorSystem"
  val KitchenActorName = "Kitchen"
  val ShelfManagerActorName = "ShelfManager"
  val OrderProcessorActorName = "OrderProcessor"
  val CourierDispatcherActorName = "CourierDispatcher"
  val CustomerSimulatorActorName = "OrderStreamSimulator"
  val CloudKitchensActorName = "CloudKitchens"
  val componentNames = Seq(OrderProcessorActorName, KitchenActorName, CourierDispatcherActorName, CustomerSimulatorActorName)

  val MaxNumberOfOrdersPerSecond = 2 // TODO read this from config
  def numberOfCouriersNeeded = MaxNumberOfOrdersPerSecond * 10 // TODO read from config

  case class StartComponent(name: String) extends JacksonSerializable

  case class StopComponent(name: String) extends JacksonSerializable

  case class RunSimulation(numberOfOrdersPerSecond: Int = 2, shelfLifeMultiplier: Float = 1)

  case object Initialize

  case object Shutdown

  case object GracefulShutdown

}


class CloudKitchens extends Actor with ActorLogging with Stash {

  import CloudKitchens._


  // TODO define custom exceptions such as detecting slow services and partitioning of
  //  nodes and raise exceptions to be handled by this supervisor
  override val supervisorStrategy = OneForOneStrategy() {
    case _: NullPointerException => Restart
    case _: Exception => Escalate
  }

  override def receive: Receive = closedForService(Map())

  def closedForService(components: Map[String, ActorRef]): Receive = {
    case Initialize =>
      log.info(s"Initializing $CloudKitchensActorName")
      self ! StartComponent(OrderProcessorActorName)
      self ! StartComponent(CourierDispatcherActorName)
      self ! StartComponent(CustomerSimulatorActorName)
      self ! StartComponent(KitchenActorName)

    case StartComponent(name) => startComponent(name, components)

    case Shutdown =>
      log.info("Shutting down CloudKitchens!")
      context.stop(self)

    case _ =>
      log.info(s"Stashing messages until initialization is complete")
      stash()
  }

  def openForService(components: Map[String, ActorRef], timeoutSchedule: Option[Cancellable] = None): Receive = {

    case StartComponent(name) => startComponent(name, components)

    case Shutdown =>
      log.info("Gracefully Shutting down CloudKitchens!")
      componentNames.filter(components.contains(_)).foreach {
        components(_) ! PoisonPill
      }

    case StopComponent(name) =>
      log.info(s"Stopping component $name")
      components.get(name).foreach(componentRef => context.stop(componentRef))

    case Terminated(ref) =>
      log.info(s"Component with ref ${ref.path} is terminated")

    case RunSimulation(ordersPerSecond, shelfLifeMultiplier) => runSimulation(components, shelfLifeMultiplier, ordersPerSecond)

  }

  import system.dispatcher

  def runSimulation(components: Map[String, ActorRef], shelfLifeMultiplier: Float, numberOfOrdersPerSecond: Int) = {
    system.scheduler.scheduleOnce(100 milliseconds) {
      log.info(s"Starting Order Simulation with components: [${components.keys.mkString(",")}]")
      components.get(OrderProcessorActorName) match {
        case Some(orderHandler) => components.get(CustomerSimulatorActorName) match {
          case Some(simulator) => simulator ! SimulateOrdersFromFile(orderHandler, numberOfOrdersPerSecond, shelfLifeMultiplier)
          case _ =>
        }
      }
    }
  }


  def startComponent(name: String, components: Map[String, ActorRef]): Unit = {
    assert(componentNames.contains(name), s"Unknown component name:$name")
    val child = name match {
      case CustomerSimulatorActorName => context.actorOf(Props[Customer], CustomerSimulatorActorName)
      case OrderProcessorActorName => context.actorOf(Props[OrderProcessor], OrderProcessorActorName)
      case KitchenActorName => context.actorOf(Kitchen.props(Kitchen.TurkishCousine, 2), s"${KitchenActorName}_${Kitchen.TurkishCousine}")
      case CourierDispatcherActorName => context.actorOf(Props[CourierDispatcher], CourierDispatcherActorName)
      case name => throw new IllegalArgumentException(s"Unknown component name:$name")
    }
    log.info(s"Starting component $name at path ${child.path}")
    context.watch(child)
    val withNewComponent = components + (name -> child)
    if (componentNames.forall(withNewComponent.contains(_))) {
      initializeComponents(withNewComponent)
      log.debug("Initialization is complete. Replaying all queued up messages.")
      unstashAll()
      context.become(openForService(withNewComponent))
    } else
      context.become(closedForService(withNewComponent))
  }

  def initializeComponents(components: Map[String, ActorRef]): Unit = components.keys.foreach(name => (name, components.get(name)) match {
    case (KitchenActorName, Some(kitchen)) =>
      kitchen ! Kitchen.InitializeKitchen(components(OrderProcessorActorName), components(CourierDispatcherActorName))
    case _ =>
  })
}

object CloudKitchenManualTest extends App {

  import CloudKitchens._

  val demo = system.actorOf(Props[CloudKitchens], CloudKitchensActorName)
  demo ! Initialize
  demo ! RunSimulation(10, 0.3f)
}
