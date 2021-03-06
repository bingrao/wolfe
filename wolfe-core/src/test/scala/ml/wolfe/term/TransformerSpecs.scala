package ml.wolfe.term

import ml.wolfe._

/**
 * @author riedel
 */
class TransformerSpecs extends WolfeSpec {

  import TermImplicits._
  import Transformer._

  "A depth first transformer" should {
    "transform a term tree in a depth first fashion" in {
      val x = Doubles.Var
      val y = Doubles.Var
      val term = x * 2.0 + x
      val expected = y * 2.0 + y
      val transformed = depthFirst(term) {
        case t if t == x => y
      }
      transformed should beStringEqual(expected)
    }
  }

  "A reusing depth first transformer" should {
    "return identical transformed terms for identical input terms" in {
      val x = Doubles.Var
      val t = x * x + x
      val transformed = depthFirstAndReuse(t) {
        case `x` => 2.0
      }
      val expected = 2.0.toConst * 2.0.toConst + 2.0.toConst
      transformed._1 should beStringEqual(expected)
      val Sum(Vector(Product(Vector(c1, c2)), c3)) = transformed._1
      (c1 eq c2) should be(true)
      (c1 eq c3) should be(true)
    }

    "return identical transformed terms for identical composed input terms" in {
      val x = Doubles.Var
      val m = mem(x convertValue (_ + 1.0))
      val t = m - m
      val transformed = depthFirstAndReuse(t) {
        case `x` => 2.0
      }
      val m2 = mem(2.0.toConst convertValue (_ + 1.0))
      val expected = m2 - m2
      transformed._1 should beStringEqual(expected)
      val Sum(Vector(t1, Product(Vector(t2, _)))) = transformed._1
      (t1 eq t2) should be(true)
    }

  }

  "A clean operation" should {
    "should not to raise a cast exception" in {
      @domain case class Theta(params: IndexedSeq[Vect])
      implicit val Thetas = Theta.Values(Seqs(Vectors(2), 3))
      def loss(t: Thetas.Term): DoubleTerm = {
        val score: DoubleTerm = t.params(0) dot t.params(0)
        ifThenElse(true.toConst)(score)(score)
      }
      val obj = loss(Thetas.Variable("t"))
      val result = (precalculate _ andThen clean)(obj)
    }
  }

  "A reusing depth last transformer" should {
    "return identical transformed terms for identical input terms" in {
      val x = Doubles.Var
      val t = x * x + x
      val transformed = depthLastAndReuse(t) {
        case `x` => 2.0
      }
      val expected = 2.0.toConst * 2.0.toConst + 2.0.toConst
      transformed._1 should beStringEqual(expected)
      val Sum(Vector(Product(Vector(c1, c2)), c3)) = transformed._1
      (c1 eq c2) should be(true)
      (c1 eq c3) should be(true)
    }
    "return identical transformed terms for identical composed input terms" in {
      val x = Doubles.Var
      val m = mem(x convertValue (_ + 1.0))
      val t = m - m
      val transformed = depthLastAndReuse(t) {
        case `x` => 2.0
      }
      val m2 = mem(2.0.toConst convertValue (_ + 1.0))
      val expected = m2 - m2
      transformed._1 should beStringEqual(expected)
      val Sum(Vector(t1, Product(Vector(t2, _)))) = transformed._1
      (t1 eq t2) should be(true)
    }

  }

  "A sum flattener" should {
    "replace nested sums with one flat sum" in {
      val x = Doubles.Var
      val term = x + x + x + x
      val expected = sum(x, x, x, x)
      val transformed = flattenSums(clean(term))
      transformed should beStringEqual(expected)
    }
  }

  "A first order sum grounder" should {
    "replace a first order sum with a propositional sum" in {
      val x = Seqs(Doubles, 3).Var
      val indices = SeqConst(0, 1, 2)
      val term = sum(indices) { i => x(i) }
      val transformed = groundSums(term)
      val i = Ints.Variable("_i")
      val expected = sum(indices.length)(x(0), x(1), x(2))
      transformed should beStringEqual(expected)
      transformed.eval(x := IndexedSeq(1.0, 2.0, 3.0)) should be(expected.eval(x := IndexedSeq(1.0, 2.0, 3.0)))
    }

    "replace a nested first order sum with a propositional sum" in {
      val x = Seqs(Seqs(Doubles,3),3).Var
      val indices = SeqConst(0, 1, 2)
      val term = sum(indices) { i => sum(indices) {j => x(i)(j) }}
      val transformed = groundSums(term)
      val expected = sum(indices.length)(
        sum(indices.length)(x(0)(0),x(0)(1),x(0)(2)),
        sum(indices.length)(x(1)(0),x(1)(1),x(1)(2)),
        sum(indices.length)(x(2)(0),x(2)(1),x(2)(2)))

      transformed should beStringEqual(expected)
    }


  }

  "A atom shattering" should {
    "create the most fine grained atoms for nested sequence variables" in {
      val n = 2
      val Graphs = Seqs(Seqs(Bools, 0, n), 1, n)
      val y = Graphs.Var
      val atom = VarAtom(y)
      val transformed = shatterAtoms(atom)
      def edgeAtomsForChild(child: Int) = {
        val parents = SeqAtom[VarSeqDom[Dom],VarSeqDom[VarSeqDom[Dom]]](atom, Graphs.indexDom.Const(child))
        new VarSeqConstructor[Dom,VarSeqDom[Dom]](
          LengthAtom(parents),
          Range(0, n) map (parent => SeqAtom[Dom, VarSeqDom[Dom]](parents, parent)),
          Graphs.elementDom
        )
      }
      def edgeAtoms() = {
        new VarSeqConstructor[VarSeqDom[Dom],VarSeqDom[VarSeqDom[Dom]]](
          LengthAtom(atom),
          Range(0, n) map (child => edgeAtomsForChild(child)),
          Graphs
        )
      }

      val expected = edgeAtoms()
      transformed should beStringEqual(expected)
    }
  }

  "A sample counter" should {
    "estimate the number of distinct samples a stochastic term can generate" in {
      val s1 = sampleSequential(0 until 4)
      val s2 = sampleSequential(0 until 2)
      val m1 = mem(s1)
      val m2 = mem(m1 + m1 + s2)
      val t = m2 + m2
      Traversal.distinctSampleCount(t) should be(8)
    }
  }

  "A precalculator" should {
    "precalculate terms inside memoized terms" in {
      implicit val rand = ml.wolfe.util.Math.random
      val x = Doubles.Var
      val t = mem(x * 4.0)
      val precalculated = precalculate(t)
      val expected = mem(Product(Vector(x, Precalculated(4.0))))
      precalculated should beStringEqual(expected)
    }
  }
}
