package reactive.coordinator

import akka.actor.Props
import reactive.coordinator.Coordinator.StartComponent
import reactive.{BaseSpec, CoordinatorActor, KitchenActor, system}

class CoordinatorSpec extends BaseSpec {

  "A Coordinator" should {
    "initialize all required components properly" in {
    }
    "initialize check heart beats regularly properly to get status" in {
    }
    "restart Kitchen if it is terminated" in {
      val coordinator = system.actorOf(Props[Coordinator], CoordinatorActor)
      coordinator ! StartComponent(KitchenActor)

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
    "restart OrderMonitor if it is terminated" in {
    }
    "shutdown the system if no activity on OrderMonitor" in {
    }
    "send fake Orders to properly completed and delivered and track average processing time" in {

    }
  }

  "An OrderProcessor" should {
    "temporarily pause incoming order processing when Shelf capacity utilization is above threshold" in {

    }

    "temporarily pause incoming order processing when dispatcher couriers is below threshold" in {

    }
  }

  "A Kitchen" should {
    "temporarily pause incoming order processing when Shelf capacity utilization is above threshold" in {

    }

    "temporarily pause incoming order processing when dispatcher couriers is below threshold" in {

    }
  }
}


