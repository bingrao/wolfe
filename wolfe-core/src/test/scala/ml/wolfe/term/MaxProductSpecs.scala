package ml.wolfe.term

import ml.wolfe.WolfeSpec

import scala.util.Random

/**
 * @author riedel
 */
class MaxProductSpecs extends WolfeSpec {

  import ml.wolfe.term.TermImplicits._

  import Argmaxer._

  "A MaxProduct algorithm" should {
    "optimize single factor objective" ignore {
      val vars = Seq(Bools.Variable("y0"), Bools.Variable("y1"))

      val obj = I(vars(0) <-> ! vars(1)) //1.1 * I(vars(0)) + 1.1 * I(vars(1))

      println("(true, true)  : " + (obj).evalUntyped(true, true))
      println("(true, false)  : " + (obj).evalUntyped(true, false))
      println("(false, true) : " + (obj).evalUntyped(false, true))
      println("(false, false) : " + (obj).evalUntyped(false, false))
      val mpParams = BPParameters(10)

      val observation = Settings.fromSeq(Seq())
      val objWithArgmaxerSpecified = obj.argmaxBy(Argmaxer.maxProduct(mpParams))
      val argmaxer = objWithArgmaxerSpecified.argmaxerImpl(vars)(observation, null)

      argmaxer.argmax()(Execution(0))

      val result = argmaxer.result.toValues(vars map (_.domain))

      println(result)
      result(0) should not be result(1)
    }

    "optimize a linear chain objective" in {
      val n = 5
      val vars = Range(0, n) map (i => Bools.Variable("y" + i))
      val length = Ints(0 until n).Var

      def local(b: BoolTerm) = I(b)
      def pair(b: (BoolTerm, BoolTerm)) = I(b._1 <-> ! b._2)

      val pairs = vars.dropRight(1) zip vars.drop(1)

      val obj = sum(vars.map(local), length) + sum(pairs.map(pair), length - 1)

      println((obj | length << 5).evalUntyped(true, true, true, true, true))
      println((obj | length << 5).evalUntyped(true, false, true, false, true))
      val mpParams = BPParameters(10)

      val observation = Settings.fromSeq(Seq(Setting.disc(5)))
      val objWithArgmaxerSpecified = obj.argmaxBy(Argmaxer.maxProduct(mpParams))
      val argmaxer = objWithArgmaxerSpecified.argmaxerImpl(vars)(observation, null)

      argmaxer.argmax()(Execution(0))

      val result = argmaxer.result.toValues(vars map (_.domain))

      println(result)
      result should be (Seq(true, false, true, false, true))
    }

    "optimize a linear chain objective in high level code" in {
      val n = 5
      val Y = Seqs(Bools, 0, n)
      def model(length: IntTerm)(y: Y.Term) = {
        sum(0 until length) { i => I(y(i))} +
          sum(0 until length - 1) { i => I(y(i) <-> ! y(i + 1))}
      }
      val mpParams = BPParameters(10)
      val result = argmax(Y)(y => model(5)(y) subjectTo (y.length === n) argmaxBy maxProduct(mpParams)).eval()
      result should be (Seq(true, false, true, false, true))
    }

    "optimize a linear chain objective in high level code with even length" in {
      val n = 10
      val Y = Seqs(Bools, 0, n)
      def model(y: Y.Term) = {
          sum(0 until n - 1) { i => 10 * I(y(i) <-> ! y(i + 1))}
      }
      val mpParams = BPParameters(10)
      val result = argmax(Y)(y => model(y) subjectTo (y.length === n) argmaxBy maxProduct(mpParams)).eval()

      for(i <- 0 until n - 1) {
        result(i) should not be result(i+1)
      }
    }

    "optimize a linear chain objective with multiple solutions" in {
      val n = 5
      val t = 3
      implicit val Y = Seqs(Ints(0 until t), 0, n)

      val mpParams = BPParameters(10)
      def model(y: Y.Term) = {
        sum(0 until n - 1) { i => I(y(i+1) === y(i)+1 || y(i+1) === y(i)-t+1)}
      } subjectTo (y.length === n) argmaxBy maxProduct(mpParams)
      val result = argmax(Y)(model).eval()

      println(result)
      model(Y.Const(result)).eval() should be (n-1)

    }

    "optimize a linear chain in a perceptron loss" in {

      implicit val random = new Random(0)

      val mpParams = BPParameters(10)

      val n = 5
      val X = Seqs(Ints(0 until 3), 0, n)
      val Y = Seqs(Bools, 0, n)
      implicit val Weights = Vectors(5)

      @domain case class Instance(x: X.Value, y: Y.Value)

      implicit val Instances = Instance.Values(X, Y)

      val train = Seq(Instance(IndexedSeq(0, 1, 2), IndexedSeq(true, false, true))).toConst

      def model(x: X.Term, w: Weights.Term)(y: Y.Term) =
        sum(0 until x.length) { i => w dot oneHot(x(i), I(y(i)))}

      def hammingDistance(y1: Y.Term, y2:Y.Term) =
        sum(0 until y1.length) { j => I(y1(j) <-> ! y2(j)) }

      def instanceScore(x: X.Term, yGold: Y.Term)(w: Weights.Term) = {
        model(x, w)(yGold) -
          { max(Y)(y => (model(x, w)(y) + hammingDistance(yGold, y)) argmaxBy maxProduct(mpParams)) }
      }

      def score(w: Weights.Term) = sum(train) { i => instanceScore(i.x, i.y)(w)}

      def stochasticScore(w: Weights.Term) = shuffled(train) { i => instanceScore(i.x, i.y)(w)}


      var oldScore = Double.NegativeInfinity
      def epochHook(i:Int, parameters:IndexedSeq[Any], score:Double) = {
        (score <= 0) should be (true)
        if(i % 10 == 0) {
          (oldScore <= score) should be(true)
          oldScore = score
        }
        ""
      }
      val adaParams = AdaGradParameters(100, 0.1, epochHook = epochHook)

      val wStar = (argmax(Weights)(stochasticScore) by adaGrad(adaParams)).eval()

      score(Weights.Const(wStar)).eval() should be (0d)

    }

  }


}
