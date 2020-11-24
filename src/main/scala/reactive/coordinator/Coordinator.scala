package reactive.coordinator

import java.time.LocalDateTime

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, OneForOneStrategy, PoisonPill, Props, Stash, Terminated, Timers}
import akka.routing.{ActorRefRoutee, BroadcastRoutingLogic, Router}
import reactive._
import reactive.config.{CoordinatorConfig, CustomerConfig, DispatcherConfig, KitchenConfig, Configs}
import reactive.coordinator.ComponentState.State
import reactive.customer.Customer
import reactive.customer.Customer.SimulateOrdersFromFile
import reactive.delivery.Dispatcher
import reactive.delivery.Dispatcher.{CourierAvailability, RecruitCouriers}
import reactive.kitchen.Kitchen
import reactive.order.{OrderMonitor, OrderProcessor}


case object ComponentState {

  sealed trait State

  case object Unknown extends State

  case object Initializing extends State

  case object UnhealthyButOperational extends State

  case object Operational extends State

  case object ShuttingDown extends State

  case object ShutDown extends State

  val heartbeatingStates = Seq(Initializing, UnhealthyButOperational, Operational)

}

case class ComponentState(key: String, state: State, actor: Option[ActorRef] = None, health: Float = 0f, updatedOn: LocalDateTime = LocalDateTime.now, lastOperational: Option[LocalDateTime] = None) {
  override def toString() = s"Component $key is $state at ${if (actor.isDefined) actor.get.path.toStringWithoutAddress else "_"} health:$health, lastUpdate: $updatedOn"
}


case class SystemState(components: Map[String, ComponentState]) {

  import ComponentState._

  /**
   * Update terminated component state
   */
  def terminated(actorRef: ActorRef): SystemState =
    components.values.filter(_.actor.isDefined).find(_ == actorRef).map(_.copy(state = ShutDown, updatedOn = LocalDateTime.now)) match {
      case Some(state: ComponentState) => update(state)
      case _ => this
    }

  def update(current: ComponentState): SystemState =
    components.get(current.key) match {
      case Some(previous) =>
        val updated = current.copy(lastOperational = if (current.state != Operational) previous.lastOperational else current.lastOperational)
        copy(components + (current.key -> updated))
      case None => copy(components + (current.key -> current))
    }

  def get(componentKey: String): ComponentState = {
    components(componentKey)
  }

  def allActorsCreated: Boolean = components.values.forall(_.actor.isDefined)

  def activeActors: Iterable[ActorRef] = components.values.filter(comp => heartbeatingStates.contains(comp.state)).flatMap(_.actor)

  def orderProcessorOption: Option[ActorRef] = actorOption(OrderProcessorActor)

  def orderMonitorOption: Option[ActorRef] = actorOption(OrderMonitorActor)

  def kitchenOption: Option[ActorRef] = actorOption(KitchenActor)

  def dispatcherOption: Option[ActorRef] = actorOption(DispatcherActor)

  def shelfManagerOption: Option[ActorRef] = actorOption(ShelfManagerActor)

  def customerOption: Option[ActorRef] = actorOption(CustomerActor)

  private def actorOption(key: String): Option[ActorRef] = components.get(key).map(_.actor).flatten
}

case object SystemState {

  import ComponentState._

  def apply(componentKeys: Seq[String], time: LocalDateTime = LocalDateTime.now()): SystemState =
    new SystemState(componentKeys.map(name => name -> ComponentState(name, Unknown, None, 0f, time)).toMap)
}


object Coordinator {

  val componentNames = Seq(OrderMonitorActor, OrderProcessorActor, KitchenActor, DispatcherActor, CustomerActor)

  case class StartComponent(name: String) extends JacksonSerializable

  case class StopComponent(name: String) extends JacksonSerializable

  case class RunSimulation(numberOfOrdersPerSecond: Int = 2, shelfLifeMultiplier: Float = 1)

  case object Initialize

  case object Shutdown

  case object GracefulShutdown

  case object CheckHeartBeat

  case object ReportStatus

}


class Coordinator extends Actor with ActorLogging with Stash with Timers with Configs {

  import Coordinator._


  // TODO define custom exceptions such as detecting slow services and partitioning of
  //  nodes and raise exceptions to be handled by this supervisor
  override val supervisorStrategy = OneForOneStrategy() {
    case _: NullPointerException => Restart
    case _: Exception => Escalate
  }

  override def receive: Receive = closedForService(SystemState.apply(componentNames))

  def closedForService(state: SystemState): Receive = {
    case Initialize =>
      log.info(s"Initializing $CoordinatorActor")
      self ! StartComponent(OrderProcessorActor)
      self ! StartComponent(OrderMonitorActor)
      self ! StartComponent(DispatcherActor)
      self ! StartComponent(CustomerActor)
      self ! StartComponent(KitchenActor)

    case StartComponent(name) => startComponent(name, state)

    case Shutdown =>
      log.info("Shutting down Reactive!")
      reportState(state)
      context.stop(self)

    case message =>
      log.debug(s"Stashing messages until initialization is complete: $message")
      stash()

  }

  def openForService(state: SystemState, heartBeatSchedule: Map[String, Cancellable]): Receive = {

    case StartComponent(name) => startComponent(name, state)

    case Shutdown =>
      log.info("Gracefully Shutting down ReactiveKitchens!")
      heartBeatSchedule.values.foreach(_.cancel())
      broadcastRouter(state).route(PoisonPill, sender())

    case StopComponent(name) =>
      log.info(s"Stopping component $name")
      heartBeatSchedule.values.foreach(_.cancel())
      state.get(name).actor.foreach(componentRef => context.stop(componentRef))

    case Terminated(ref) =>
      log.info(s"Component with ref ${ref.path} is terminated")
      context.become(openForService(state.terminated(ref), heartBeatSchedule - ref.path.toStringWithoutAddress))

    case RunSimulation(ordersPerSecond, shelfLifeMultiplier) =>
      runSimulation(state, shelfLifeMultiplier, ordersPerSecond)

    case componentState: ComponentState => evaluateState(state.update(componentState))
      val updatedState = evaluateState(state.update(componentState))
      log.debug(s"Received update: $componentState")
      context.become(openForService(updatedState, updateSchedule(heartBeatSchedule, componentState)))

    case CourierAvailability(availability, _)  =>
      if (availability == 0)
        sender() ! RecruitCouriers(dispatcherConf.numberOfCouriersToRecruitInBatches, state.shelfManagerOption.get, state.orderMonitorOption.get)

    case other => log.error(s"Received unrecognized message $other from sender: ${sender()}")
  }

  import system.dispatcher

  def evaluateState(systemState: SystemState): SystemState = {
    //    systemState.components.values.filter(_.state == foreach{
    //      case componentState => componentState
    //    }
    systemState
  }

  def reportState(systemState: SystemState): Unit =
    systemState.components.values.foreach(compState => log.info(compState.toString()))

  def runSimulation(state: SystemState, shelfLifeMultiplier: Float, numberOfOrdersPerSecond: Int): Unit =
    system.scheduler.scheduleOnce(coordinatorConf.initializationTimeInMillis) {
      (state.customerOption, state.orderProcessorOption) match {
        case (Some(customer), Some(orderProcessor)) =>
          log.info(s"Starting Order Simulation with $numberOfOrdersPerSecond orders per second, and shelf life multiplier:$shelfLifeMultiplier")
          customer ! SimulateOrdersFromFile(orderProcessor, numberOfOrdersPerSecond, shelfLifeMultiplier)
        case _ => log.error("Initialization not complete to start Customer simulation")
      }
    }


  def startComponent(name: String, state: SystemState): Unit = {
    assert(componentNames.contains(name), s"Unknown component name:$name")
    val child = name match {
      case CustomerActor => context.actorOf(Props[Customer], CustomerActor)
      case OrderProcessorActor => context.actorOf(Props[OrderProcessor], OrderProcessorActor)
      case OrderMonitorActor => context.actorOf(Props[OrderMonitor], OrderMonitorActor)
      case KitchenActor => context.actorOf(Kitchen.props(Kitchen.TurkishCousine, 2), s"${KitchenActor}_${Kitchen.TurkishCousine}")
      case DispatcherActor => context.actorOf(Props[Dispatcher], DispatcherActor)
      case name => throw new IllegalArgumentException(s"Unknown component name:$name")
    }
    log.info(s"Starting component $name at path ${child.path}")
    context.watch(child)
    val withNewComponent = state.update(state.get(name).copy(actor = Some(child), state = ComponentState.Initializing, updatedOn = LocalDateTime.now()))
    if (withNewComponent.allActorsCreated) {
      broadcastRouter(withNewComponent).route(withNewComponent, self)
      log.debug("Initialization is complete. Replaying all queued up messages.")
      unstashAll()
      context.become(openForService(withNewComponent, createHeartBeatSchedule(state)))
    } else {
      context.become(closedForService(withNewComponent))
    }
  }

  private def createHeartBeatSchedule(systemState: SystemState): Map[String, Cancellable] = systemState.activeActors.map(createHeartBeatSchedule).toMap

  private def createHeartBeatSchedule(actorRef: ActorRef): (String, Cancellable) =
    actorRef.path.toStringWithoutAddress -> context.system.scheduler.scheduleOnce(coordinatorConf.heartBeatScheduleMillis) {
      actorRef ! ReportStatus
    }

  private def updateSchedule(schedule: Map[String, Cancellable], heartBeat: ComponentState): Map[String, Cancellable] = {
    schedule.get(heartBeat.actor.get.path.toStringWithoutAddress).foreach(_.cancel())
    schedule + createHeartBeatSchedule(heartBeat.actor.get)
  }

  def broadcastRouter(systemState: SystemState): Router = Router(BroadcastRoutingLogic(), systemState.activeActors.map(ActorRefRoutee(_)).toIndexedSeq)

}

