package scalapplcodefest.term

import scalapplcodefest.value.Doubles

/**
 * @author Sebastian Riedel
 */
trait DoubleTerm extends Term[Double] {
  def default = 0.0
  def domain[C >: Double] = Constant(Doubles).asInstanceOf[Term[Set[C]]]
}