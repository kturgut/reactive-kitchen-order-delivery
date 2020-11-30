package reactive.coordinator

import akka.actor.Props
import akka.testkit.TestProbe
import reactive.coordinator.ComponentState.Operational
import reactive.coordinator.Coordinator.StartComponent
import reactive.{BaseSpec, CoordinatorActor, DispatcherActor, KitchenActor, OrderMonitorActor}

class CoordinatorSpec extends BaseSpec {

  "A Coordinator" should {
    "initialize all required components properly" in {
    }
    "initialize check heart beats regularly properly to get status" in {
    }
    "restart Kitchen if it is terminated" in {
      val coordinator = system.actorOf(Props[Coordinator], CoordinatorActor)
      val dispatcher = TestProbe(DispatcherName)
      val orderMonitor = TestProbe(OrderMonitorName)
      coordinator ! StartComponent(KitchenActor)
      val dispatcherState = ComponentState(DispatcherActor, Operational, Some(dispatcher.ref),1f)
      val orderMonitorState = ComponentState(OrderMonitorActor, Operational, Some(orderMonitor.ref),1f)
      val systemState = SystemState(Map(DispatcherActor->dispatcherState,OrderMonitorActor->orderMonitorState))
      //TODO
    }
    "restart Dispatcher if it is terminated" in {
    }
    "tell Dispatcher to recruit more Couriers if available Couriers remain at 0 for more than threshold" in {
    }
    "restart OrderProcessor if it is terminated" in {
    }
    "restart OrderMonitor if it is terminated" in {
    }
    "reissue freshly received Orders to Kitchen after a recovery of OrderMonitor if no activity from Kitchen within threshold" in {
    }

    "shutdown the system if no activity on OrderMonitor" in {
    }

    "send fake Orders to properly completed and delivered and track average processing time" in {

    }
  }
}


