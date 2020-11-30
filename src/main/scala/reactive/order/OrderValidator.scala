package reactive.order

import akka.event.{LoggingAdapter, NoLogging}
import reactive.order.Temperature.UnknownTemperature

object OrderValidator {

  trait Issue {
    def message:String
  }

  // TODO put these in config
  val MinRateRange = 0f
  val MaxDecayRate = 10f

  val MinShelfLife = 0
  val MaxShelfLife = 10000
  val ShelfLifeTooLowTreshold = 6

  sealed trait ValidationError extends Issue
  case class NegativeShelfLife(message:String) extends ValidationError
  case class InvalidTemperatureRating(message:String) extends ValidationError
  case class InvalidDecayRate(message:String) extends ValidationError
  case class InvalidShelfLife(message:String) extends ValidationError

  sealed trait ValidationWarning extends Issue
  case class ProductMightExpireBeforeDelivery(message:String) extends ValidationError


  /**
   * Validate given Order. Log errors and warnings. If valid (ie. no errors) send back the order, else return None.
   */
  def validate(order:Order, log:LoggingAdapter=NoLogging):Option[Order] = {
    var warnings = List.empty[Issue]
    var errors = List.empty[Issue]
    if (order.shelfLife <= 0)
      errors = NegativeShelfLife(s"Shelf life ${order.shelfLife} can't be negative for order with id:${order.id}")::errors
    else if (order.shelfLife < ShelfLifeTooLowTreshold)
      warnings = ProductMightExpireBeforeDelivery(s"Shelf life ${order.shelfLife} is lower than ideal for order with id:${order.id}")::warnings

    if (order.temperature == UnknownTemperature)
      errors = InvalidTemperatureRating(s"Temperature rating ${order.temp} not supported  for order with id:${order.id}")::errors

    if (order.decayRate <= MinRateRange || order.decayRate>MaxDecayRate)
      errors = InvalidDecayRate(s"Decay rate must be between [$MinRateRange,$MaxDecayRate] but is: ${order.decayRate} for order with id:${order.id}")::errors

    if (order.shelfLife <= MinShelfLife || order.shelfLife>MaxShelfLife)
      errors = NegativeShelfLife(s"Shelf life must be between [$MinShelfLife,$MaxShelfLife] but is: ${order.decayRate} for order with id:${order.id}")::errors

    logIssues(errors,warnings,log)

    if (errors.isEmpty) Some(order) else None
  }

  def logIssues(errors:Seq[Issue], warnings:Seq[Issue], log:LoggingAdapter) = {
    errors.foreach(issue=>log.error(issue.message))
    warnings.foreach(issue=>log.warning(issue.message))
  }


}
