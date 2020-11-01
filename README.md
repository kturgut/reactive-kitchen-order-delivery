# Shelf Optimization Algorithm

## Overview
  This algorithm optimizes the cumulative value of all values preserved on Shelf.
- Value: for Each product on shelf is computed based on given formula
- TotalShelfValue: Sum of all product values on the shelf at given point in time
- CumulativeShelfValue: Cumulative total shelf value of aggregated at every second for a given period of time, in terms of seconds
- SimulationTimeInSeconds: Defines how many seconds into future that we will simulate incoming orders 

## Steps 
0- Create 4 shelves (Hot,Cold,Frozen,Overflow) with given decayRatio constants (1,1,1,2) and capacities (10,10,10,15)
   - For each shelf create two priorityQueues:
        - HiqhValue product queue
        - LowValue product queue 

1- As orders are being received, calculate rolling statistics on average shelf life and decay ratio for each order grouped by temperature

2 - On all the shelves, remove orders with value < 0

3- When a new order is received at (time0), put it into Overflow shelf and start the shelf optimization algorithm

4- While (overflow shelf is not empty, and a product in overflow shelf can be moved to its own shelf)
      val productToMove = overflow.highValue.dequeue
      val tempTotalShelfValue = calculateTotalShelfValue (move(productToMove, shelfFor(productToMove.temperature))
      simulate)
      
4-REFINED  For each permutation of values in Overflow Bin {
      case permutation: List[Order]= while (permutation is not empty, and a product in overflow shelf can be moved to its own shelf)
          val productToMove = permutation.head; permutation = permutation.tail
          val tempTotalShelfValue = calculateTotalShelfValue (move(productToMove, shelfFor(productToMove.temperature))                       
    }
            
      
## Move (product, targetShelf): TotalShelfValue
  - if targetShelf has capacity, move it to target shelf
        targetShelf.highPriority.enqueue(product)
        targetShelf.lowPriority.enqueue(product)      
  - if targetShelf is full && product.value> targetShelf.lowValue.peek.value
        targetShelf.highPriority.enqueue(product)
        targetShelf.lowPriority.enqueue(product)
        val productToMove = targetShelf.lowPriority.dequeue
        targetShelf.highPriority.remove(productToMove)
        if (targetShelf!=Overflow)
          move(productToMove, Overflow)
        else {
          notify product is going to be discarded(productToMove)
        }
               
   