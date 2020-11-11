# CloudKitchens 

CloudKitchens is a real-time system that emulates the full-fillment of delivery orders for a kitchen.

# Components
# Component Overview
1. CloudKitchens. Is the main controller, its main function is to initialize the system and monitor health. (Though I did not have time to implement heartbeat and also provide tests for how it would bring up components that are crashed or slow performing)
1. Customer. Simulates customers sending orders. Currently, reads from a file and sends as a stream. It is possible to turn this into an ongoing stream.
1. OrderProcessor routes orders to Kitchens. As the only "Persistent" actor in the system, it also has a second role as the maintainer of all LifeCycle events for all orders. 
1. Kitchen prepares the orders and hands them off to ShelfManager for Storage. It also notifies CourierDispatcher
1. Courier delivers the orders routed by Courier Dispatcher. Courier notifies its state (onAssignment|Available) or CourierDispatcher as it processes orders so that a Courier does not get assigned multiple orders
1. ShelfManager manages Storage till packaged products get delivered to customers by Couriers. It maintains a cache of active courier assignments to packagedProducts.
![Components](images/ComponentDiagram.png)

## Main Flows
### Successful Delivery
* Customer sends Orders to OrderProcessor
* Orders are sent to Kitchen.
* Kitchen prepares a "packaged product" for the order and passes it to ShelfManager for storage till pickup. 
  * Kitchen simultaneously notifies CourierDispatcher for upcoming delivery assignment
* CourierDispatcher assigns a Courier for incoming packaged product delivery request and routes it to one of its Couriers
  for pickup and delivery, using RoundRobin logic among its available Couriers
* Courier upon receiving request for delivery
   * Creates a CourierAssignment and sends it to ShelfManager
   * Sends OnAssignment notice to CourierDispatcher to be taken off the RoundRobin router
* Courier after 2-6 seconds sends a PickupRequest to ShelfManager. 
  * ShelfManager responds with Pickup
* Courier sends DeliveryAcceptanceRequest to Customer, effectively simulating signature. 
  * Customer responds with DeliveryAcceptance 
* All order life cycle events (Order, PackagedProduct, DeliveryComplete, DiscardOrder) are sent to OrderProcessor which keeps a durable history, and and a cache for all active orders

![DeliveryComplete](images/DeliveryCompleteSequence.png)
### Discarded Order
* ShelfManager calls ShelfLifeOptimization logic either periodically (every 1 second) or as products are added to the shelf
    * Any expired products will be notified to Courier as DiscardOrder. (TODO Also notify customer)
* Courier may get back a DiscardOrder message as response to PickupRequest if the product has expired just recently
* Courier will transition into available state by sending Available message to Courier so he can get assigned to new orders for delivery     

![DiscardOrder](images/DiscardOrderSequence.png)

# Notes for Future Architectural Enhancements 
1. OrderProcessor having dual roles is good idea for production system. Since you want to be able to independently scale Order Processing to ensure uptime. This is done solely for practical reasons to save time.
1. Enhance BackPressure handling logic between components. ie. Between OrderProcessor and Kitchen, by getting feedback on ShelfManager's state.
1. Circuit Breakers between major components. ie. OrderProcessor Kitchen.  OrderProcesor CourierDispatcher etc.
1. Switch to a more decoupled architecture wher life-cycle events are published to a "bus". Currently OrderProcessor is acting that role actually, but for practical reasons I kept it as it is.
1. Of course there is dozens of things come to mind like running it on cluster, and replication of data, putting CDN on top, Rest APIs etc.. but I will leave it at that.

## About AKKA Persistent Actors:
1.   Stateful Actors preserve state. They are able to replay messages that were sent to them during crash
1.   They also can create 'Snapshot's of their states so recovery can happen instantly. Not implemented yet. TODO
1.   Akka supports various persistence options, like Relational, Cassandra etc. I chose LevelDb for simplicity
1.   It is also possible to do a Persistent Query on PersistentActors to query state. Not implemented yet. TODO


# ShelfLife Optimization algorithm
 
### Objective
 Storage Product Life Cycle Optimization Algorithm has the following objectives:
 - Minimize the number of discarded products due to expiration
 - Minimize the time to discard an order if an order has to be discarded due to insufficient shelf capacity
 - Maximize the shelfLife 'value' of the products delivered to customers

### Background
1. Storage can be configured to work with multiple shelves each of which provide different Capacity, decayRateModifier
1. Each Shelve can declare what "temperatures" it supports.
1. Overflow shelf can accommodate products of all temperatures but with higher decayRateModifier (currently 2)
1. 'Hot', 'Frozen', 'Cold' shelves only accept products that matches the corresponding temperatures.
1. Each order is defined by shelfLife and decayRate.
 
### Constraints
1. Couriers are assigned to a single PackagedProduct and they are supposed to pick up the product they are assigned
1. Couriers are expected to come between 2 to 6 seconds after their assignment
1. Value of a product on shelf is calculated as: 
   1. (shelfLife - orderAge - decayRate * orderAge * shelfDecayModifier) / shelfLife
1. Storage is to have single shelf for each temperature supported("hot", "cold","frozen") each with limited capacity, default 10

### Approach
 1. Shelves keep the products in a sorted set with increasing order of "value"
 1. All new products received are put into Overflow shelf first.
 1. Shelf optimization is done 
     1. when new products are added to Storage 
     1. periodically on schedule (currently every second)
 1. Whenever a public API of storage is accessed, refresh the PackagedProduct definitions, and take a snapshot of the Storage, effectively making a copy. 
 1. PackagedProduct maintains 'remainingLife' and 'lastUpdate' and given the decayRateModifer constant of the shelf they are on, can calculate current value
 1. PackagedProduct also can calculate expected expirationInMilliseconds and expectedPickupTimeWindow
   
### Steps
 1. Discard all expired products on all shelves
 1. Move products in overflow shelf to corresponding 'temperature sensitive' (aka target) shelves with lower decayRate modifier while there is capacity on those shelves
 Note: Current implementation assumes single "target" shelf for each temperature.
 1. If overflow shelf is full
    1. Expire products that will not be be deliverable within the  expected pickup window (using the 2 second minimum delay)
    1. Replace products that can benefit with increased shelf life if swapped by a product in target shelf, if the swap will not put the other in to critical zone. 
       1. CriticalZone is controlled by a constant currently with default=2 seconds
 1. If the overflow shelf is still full
   1. While overflow is over capacity: discard the product with the newest order creation timestamp. This is so that we minimize the time the customer gets feedback and we reduce waste if the product is already on the way to delivery
 
# Considerations for Improvement
1. Swapping products between overflow and other shelves can be started before waiting for overflow shelf to be full to further optimize shelf life 
1. If CourierAssignments can be adjusted by ShelfManager based on what product is at risk of expiration, it would improve customer satisfaction
1. Further improvement after relaxing courier assignments might be that: we can learn from past orders as to what the likelyhood of a particular 'temperature sensitive' shelf to open up might be, and run a short simulation into the future to decide which product to discard.  


# Getting Started
1. CloudKitchens actor is the main controller that starts the entire ActorSystem.
## Run main Simulation
1. Find CloudKitchenManualTest Application under src/main/scala/reactive/CloudKitchens file. It is a runnable Application. Right mouse click on the green triangle in Intellij and run it.
1. Currently it sends two messages to CloudKitchens actor that it starts
     1. demo ! Initialize
     1. demo ! RunSimulation(2,1f)
1. After 6 seconds of no activity, program will automatically shutdown.
## Control Nobs: 
1. RunSimulation message is forwarded to Customer Actor which simulates a customer sending orders by reading preexisting orders saved under src/main/resources/orders.json
1. Please note that the two parameters inside the RunSimulation message are for
     1. First: numberOfOrdersPerSecond, to control the throttle
     1. Second: decayRate multiplier. For example if you put 0.2f, products on shelf will decay about 5 times faster.
1. There are about 100 messages in the orders.json file. If you want to run with a smaller number of orders, to trace activity you can enable the commented line #75 in Customer.scala file
1. Currently,  Shelf contents are only being printed in detail if overflow gets full. This is controlled by Line 76 and 77 in ShelveManager.scala. You can relax that constraint and print storage contents more often
   1. For example  change this:   "if (storage.isOverflowFull()) storage.reportStatus(true)" to " if (storage.shelves(Temperature.All).products.size > 10) ..."
## IMPORTANT NOTE: 
   1. OrderProcessor stores its persistence files under /target/cloudKitchens/journal folder. 
   1. If you re run the app after 1st run, you may get some confusing recovery messages. I did not have time to clean the logs that up. So to avoid those, you may want to delete the files under journal before running again.
   1. In case you delete the 'journal' under target/cloudKitchens folder, you may get an exception so make sure the folder structure is there   "target/cloudKitchens/journal"
## Run Tests
1. Unit and Integration tests though limited are provided under src/test/scala/ folders. Right click and run any test or an entire folder.

## Contact Me If you have questions running it
Kagan 650 438 0401  kagan@brane.com

