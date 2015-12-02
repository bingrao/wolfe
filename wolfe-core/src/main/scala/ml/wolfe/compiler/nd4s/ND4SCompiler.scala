package ml.wolfe.compiler.nd4s

import ml.wolfe.Tensor
import ml.wolfe.compiler.{DelayedCompiler, Module}
import ml.wolfe.term._
import org.nd4j.linalg.api.ndarray.INDArray
import org.scalactic._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

import PimpMyND4S._
import org.nd4s.Implicits._
import org.nd4j.linalg.ops.transforms.Transforms._

/**
  * @author rockt
  */
object ND4SCompiler extends DelayedCompiler {

  import Typer._

  implicit def asGood(box: Box): Box Or Every[CompilationError] = Good(box)

  implicit def asBad(error: CompilationError): Bad[Nothing, One[CompilationError]] = Bad(One(error))

  def compileBox(termToCompileToBox: Term[Any],
                 paramBindings: Bindings, inputBindings: Bindings,
                 var2InputBox: mutable.HashMap[Var[Any], InputBox],
                 var2ParamBox: mutable.HashMap[Var[Any], ParamBox],
                 var2Substitution: Map[Var[Any], Box] = Map.empty): Box Or Every[CompilationError] = {

    def comp(term: Term[Any]) = compileBox(term, paramBindings, inputBindings, var2InputBox, var2ParamBox, var2Substitution)

    def tensorDom(dom: Dom[Any]): TensorDom Or One[CompilationError] = dom match {
      case t: TensorDom => Good(t)
      case _ => Bad(One(CompilationError(termToCompileToBox, s"Expected a tensor domain but got: $dom")))
    }

    def productDom(dom: Dom[Any]): ProductDom[Product] Or One[CompilationError] = dom match {
      case t: ProductDom[_] => Good(t)
      case _ => Bad(One(CompilationError(termToCompileToBox, s"Expected a product domain but got: $dom")))
    }

    def check[A](predicate: Boolean, result: A, msg: String) =
      if (predicate) Good(result) else Bad(One(CompilationError(termToCompileToBox, msg)))

    termToCompileToBox match {
      case v: Var[_] if var2Substitution.contains(v) =>
        var2Substitution(v)

      case v: Var[_] if paramBindings.contains(v) =>
        var2ParamBox.getOrElseUpdate(v, new ParamBox(v, deriveDomainFromValue(paramBindings(v))))

      case v: Var[_] if inputBindings.contains(v) =>
        var2InputBox.getOrElseUpdate(v, new InputBox(v, deriveDomainFromValue(inputBindings(v))))

      case v: Var[_] =>
        Bad(One(CompilationError(v, s"Variable $v has not been bound yet. Bind it using init() or forward().")))

      case Sigmoid(arg) =>
        for (argBox <- comp(arg); d <- tensorDom(argBox.dom))
          yield new SigmoidBox(argBox, d)

      case TensorMul(arg1, arg2) =>
        for (arg1Box <- comp(arg1); d1 <- tensorDom(arg1Box.dom);
             arg2Box <- comp(arg2); d2 <- tensorDom(arg2Box.dom);
             d <- check(d1.dims(1) == d2.dims(0), TensorDom(List(d1.dims(0), d2.dims(1))),
               s"Tensor multiplication dimension mismatch: $d1 * $d2"))
          yield new TensorProductBox(arg1Box, arg2Box, d)

      case ComponentPlus(arg1, arg2) =>
        for (arg1Box <- comp(arg1); d1 <- tensorDom(arg1Box.dom);
             arg2Box <- comp(arg2); d2 <- tensorDom(arg2Box.dom);
             d <- check(d1.dims == d2.dims, d1, s"Tensor plus domains don't match: $d1 != $d2"))
          yield new TensorPlusBox(arg1Box, arg2Box, d)

      case GetElement(arg, element) =>
        for (argBox <- comp(arg); d <- productDom(argBox.dom))
          yield new GetElementBox(argBox, element, d.doms(element))

      case Foldl(seq, init, op) =>
        def boxOp(current: Box, elem: Box) = {
          compileBox(op.body, paramBindings, inputBindings, var2InputBox, var2ParamBox,
            Map(op.argument1 -> current, op.argument2 -> elem)).get
        }

        for (seqBox <- comp(seq); initBox <- comp(init)) yield {
          new FoldlBox(seqBox, initBox, boxOp)
        }

      case _ => Bad(One(CompilationError(termToCompileToBox, "Not supported for ND4S compilation yet: " + termToCompileToBox)))
    }
  }

  def compile[T](term: Term[T], paramBindings: Bindings, inputBindings: Bindings) = {
    val var2InputBox = new mutable.HashMap[Var[Any], InputBox]()
    val var2ParamBox = new mutable.HashMap[Var[Any], ParamBox]()

    for (box <- compileBox(term, paramBindings, inputBindings, var2InputBox, var2ParamBox)) yield {
      new Module[T] {

        def gradient[G](param: Var[G]) = {
          val paramBox = var2ParamBox(param)
          //paramBox.grad.asInstanceOf[G] //todo: this needs to be converted back
          paramBox.grad.tensor.asInstanceOf[G]
        }

        def param[P](param: Var[P]) = {
          val paramBox = var2ParamBox(param)
          paramBox.output.asInstanceOf[P]
        }

        def init(bindings: Binding[Any]*) = {
          for (binding <- bindings; box <- var2ParamBox.get(binding.variable)) {
            box.output = Table.toTable(binding.value)
          }
        }

        def forward(bindings: Binding[Any]*) = {
          for (binding <- bindings; box <- var2InputBox.get(binding.variable)) {
            box.output = Table.toTable(binding.value)
          }
          box.forward()
        }

        def output() = {
          box.output.tensor.asInstanceOf[T] //todo: this needs to convert back
        }

        def backward(output: T) = {
          box.backward(Table.toTable(output.asInstanceOf[Tensor]))
        }

        def updateParameters(learningRate: Double) = ???
      }
    }
  }
}

class Table(numTables: Int = 0) {

  var tensor: Tensor = _
  val children = Array.ofDim[Table](numTables)

  def +=(that: Table, scale: Double = 1.0): Unit = {
    tensor += that.tensor * scale
    for (i <- children.indices) children(i) +=(that.children(i), scale)
  }

}

object Table {
  //fixme: where is this used?
  /*
  def apply(tensors: Tensor*) = {
    val result = new Table(tensors.length)
    for (i <- tensors.indices) {
      result.children(i) = new Table()
      result.children(i).tensor = tensors(i)
    }
    result
  }
  */

  def toTable(value: Any): Table = value match {
    case s:Seq[_] =>
      val values = s.map(toTable).toIndexedSeq
      val table = new Table(values.length)
      for (i <- values.indices) table.children(i) = values(i)
      table
    case p: Product =>
      val values = p.productIterator.map(toTable).toIndexedSeq
      val table = new Table(values.length)
      for (i <- values.indices) table.children(i) = values(i)
      table
    case t: INDArray =>
      val result = new Table()
      result.tensor = t.asInstanceOf[Tensor]
      result
    case _ => sys.error(s"We can't convert $value of class ${value.getClass} to table")
  }
}


trait Box {
  def forward()

  def backward(gradOutput: Table)

  def output: Table

  def gradInputs: Table

  def dom: Dom[Any]

}

class ParamBox(val variable: Var[Any], val dom: Dom[Any]) extends Box {
  var output: Table = _
  var grad: Table = _
  var gradInputs: Table = _

  def backward(gradOutput: Table) = grad match {
    case null => grad = gradOutput
    case _ => grad += gradOutput
  }

  def forward() = {

  }

  def update(learningRate: Double): Unit = {
    output +=(grad, learningRate)
  }

}

class InputBox(val variable: Var[Any], val dom: Dom[Any]) extends Box {
  var output: Table = _
  var gradInputs: Table = _

  def forward() = {

  }

  def backward(gradOutput: Table) = {

  }

}

class SigmoidBox(input: Box, val dom: TensorDom) extends Box {
  val output = new Table(1)
  val gradInputs = new Table(1)

  def forward() = {
    input.forward()
    output.tensor = sigmoid(input.output.tensor)
  }

  def backward(gradOutput: Table) = {
    val y = sigmoid(input.output.tensor)
    gradInputs.tensor = (y :* (-y + 1)) :* gradOutput.tensor
    input.backward(Table.toTable(gradInputs.tensor))
  }
}

class TensorProductBox(arg1: Box, arg2: Box, val dom: TensorDom) extends Box {
  val output = new Table(1)
  val gradInputs = new Table(2)

  def forward() = {
    arg1.forward()
    arg2.forward()
    output.tensor = arg1.output.tensor ** arg2.output.tensor
  }

  def backward(gradOutput: Table) = {
    gradInputs.children(0) = Table.toTable(gradOutput.tensor outer arg2.output.tensor)
    gradInputs.children(1) = Table.toTable(arg1.output.tensor ** gradOutput.tensor)
    arg1.backward(gradInputs.children(0))
    arg2.backward(gradInputs.children(1))
  }
}

class TensorPlusBox(arg1: Box, arg2: Box, val dom: TensorDom) extends Box {
  val output = new Table(1)
  val gradInputs = new Table(2)

  def forward() = {
    arg1.forward()
    arg2.forward()
    output.tensor = arg1.output.tensor + arg2.output.tensor
  }

  def backward(gradOutput: Table) = {
    gradInputs.children(0).tensor = gradOutput.tensor
    gradInputs.children(1).tensor = gradOutput.tensor
  }
}


class GetElementBox(arg: Box, index: Int, val dom: Dom[Any]) extends Box {
  var output: Table = _

  def forward() = {
    arg.forward()
    output = arg.output.children(index)
  }

  var gradInputs: Table = _

  def backward(gradOutput: Table) = {

  }
}

class FoldlBox(val argSeq: Box, val init: Box, op: (Box, Box) => Box) extends Box {
  var output: Table = _
  val gradInputs = new Table(0)

  //unroll the graph by creating new boxes for each
  val resultBoxes = new ArrayBuffer[Box]
  val elemBoxes = new ArrayBuffer[Box]

  val elemDom = init.dom

  var lastResultBox = init

  def unrollMore(howMany: Int): Unit = {
    for (i <- resultBoxes.length until resultBoxes.length + howMany) {
      val elem = new GetElementBox(argSeq, i, elemDom)
      elemBoxes += elem
      lastResultBox = op(lastResultBox, elem)
      resultBoxes += lastResultBox
    }
  }

  unrollMore(2)

  def forward() = {
    argSeq.forward()
    val currentLength = argSeq.output.children.length
    if (currentLength > resultBoxes.length) {
      unrollMore(currentLength - resultBoxes.length)
    }
    init.forward()
    for (i <- 0 until currentLength) {
      elemBoxes(i).forward()
      resultBoxes(i).forward()
    }
    output = resultBoxes(currentLength - 1).output
  }

  def backward(gradOutput: Table) = {
    //where does the gradient go?
    val currentLength = argSeq.output.children.length
    var currentGradOutput = gradOutput
    for (i <- currentLength - 1 to 0 by -1) {
      resultBoxes(i).backward(currentGradOutput)
      currentGradOutput = resultBoxes(i).gradInputs.children(0) //or (1)?
      elemBoxes(i).backward(resultBoxes(i).gradInputs.children(1))
    }
    init.backward(currentGradOutput)
  }

  def dom = resultBoxes.head.dom
}
