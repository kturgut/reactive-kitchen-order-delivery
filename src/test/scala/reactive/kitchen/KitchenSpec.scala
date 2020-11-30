package reactive.kitchen

import akka.actor.Props
import akka.testkit.{TestActorRef, TestProbe}
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.ReportStatus
import reactive.coordinator.{ComponentState, SystemState}
import reactive.delivery.Dispatcher.CourierAvailability
import reactive.storage.ShelfManager.CapacityUtilization
import reactive.{BaseSpec, DispatcherActor, OrderMonitorActor}

class KitchenSpec extends BaseSpec {

  "An Kitchen" should {

    "initialize when it receives valid system state" in {
      val (kitchen, systemState) = kitchenAndInitialSystemState()
      kitchen ! systemState
      assert(expectMsgType[ComponentState].state == Operational)
      kitchen ! ReportStatus
      assert(expectMsgType[ComponentState].state == Operational)
    }

    "temporarily pause incoming order processing when Shelf capacity utilization is above threshold" in {
      val (kitchen, systemState) = kitchenAndInitialSystemState()
      kitchen ! systemState
      assert(expectMsgType[ComponentState].state == Operational)

      kitchen ! CapacityUtilization(1, 1, 0)
      // TODO
    }

    "temporarily pause incoming order processing when dispatcher couriers is below threshold" in {
      val (kitchen, systemState) = kitchenAndInitialSystemState()
      kitchen ! systemState
      assert(expectMsgType[ComponentState].state == Operational)

      kitchen ! CourierAvailability(0, 20)
    }

    "resume processing incoming order processing when Shelf capacity utilization is below threshold" in {
      val (kitchen, systemState) = kitchenAndInitialSystemState()
      kitchen ! systemState
      assert(expectMsgType[ComponentState].state == Operational)

      kitchen ! CapacityUtilization(1, 1, 0)
      kitchen ! CapacityUtilization(0, 0, 10)
      // TODO
    }

    "resume processing incoming order processing when dispatcher couriers is above threshold" in {
      val (kitchen, systemState) = kitchenAndInitialSystemState()
      kitchen ! systemState
      assert(expectMsgType[ComponentState].state == Operational)

      kitchen ! CourierAvailability(0, 20)
      kitchen ! CourierAvailability(5, 20)
      // TODO
    }
  }


  def kitchenAndInitialSystemState(): (TestActorRef[Kitchen], SystemState) = {
    val kitchen = TestActorRef[Kitchen](Props(new Kitchen(KitchenName)))
    val dispatcher = TestProbe(DispatcherName)
    val orderMonitor = TestProbe(OrderMonitorName)
    val dispatcherState = ComponentState(DispatcherActor, Operational, Some(dispatcher.ref), 1f)
    val orderMonitorState = ComponentState(OrderMonitorActor, Operational, Some(orderMonitor.ref), 1f)
    val systemState = SystemState(Map(DispatcherActor -> dispatcherState, OrderMonitorActor -> orderMonitorState))
    (kitchen, systemState)
  }

}
