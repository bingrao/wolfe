package ml.wolfe.macros

import ml.wolfe.{MaxProduct, BruteForceOperators, Wolfe, WolfeSpec}

/**
 * @author Sebastian Riedel
 */
class ArgmaxSpecs extends WolfeSpec {

  import OptimizedOperators._

  "An argmax operator" should {
    "return the argmax of a one-node sample space and atomic objective " in {
      import Wolfe._
      val actual = argmax { over(Range(0, 5)) of (_.toDouble) }
      val expected = BruteForceOperators.argmax { over(Range(0, 5)) of (_.toDouble) }
      actual should be(expected)
    }

    "return the argmax of a one-node sample space and atomic objective with implicit domain" in {
      import Wolfe._
      val actual = argmax { over[Boolean] of (I(_)) }
      val expected = BruteForceOperators.argmax { over[Boolean] of (I(_)) }
      actual should be(expected)
    }

    "return the argmax of a two node case class sample space, one observation and atomic objective" in {
      import Wolfe._
      case class Data(x: Boolean, y: Boolean)
      val actual = argmax { over(Wolfe.all(Data)) of (d => I(!d.x || d.y)) st (_.x) }
      val expected = BruteForceOperators.argmax { over(Wolfe.all(Data)) of (d => I(!d.x || d.y)) st (_.x) }
      actual should be(expected)
    }
    "use the algorithm in the maxBy annotation " in {
      import Wolfe._
      case class Data(x: Boolean, y: Boolean, z: Boolean)
      implicit def data = Wolfe.all(Data)
      @MaxByInference(MaxProduct(_, 10))
      def tenIterations(d: Data) = I(d.x && d.y) + I(d.y && !d.z) + I(d.z && !d.x)
      @MaxByInference(MaxProduct(_, 1))
      def oneIteration(d: Data) = tenIterations(d)
      val actual = argmax { over[Data] of tenIterations }
      val approximate = argmax { over[Data] of oneIteration }
      val expected = BruteForceOperators.argmax { over[Data] of tenIterations }
      actual should be(expected)
      actual should not be approximate
    }
  }

}