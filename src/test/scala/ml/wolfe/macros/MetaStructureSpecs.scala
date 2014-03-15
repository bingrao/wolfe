package ml.wolfe.macros

import ml.wolfe.{Wolfe, WolfeSpec}

/**
 * @author Sebastian Riedel
 */
class MetaStructureSpecs extends WolfeSpec {

  implicit class StructureTest[T](structure:Structure[T]) {
    def mustBeIsomorphicTo(iterable:Iterable[T]) {
      val expectedSize = iterable.size
      val expectedSet = iterable.toSet
      var count = 0
      structure.resetSetting()
      while (structure.hasNextSetting) {
        structure.nextSetting()
        val value = structure.value()
        expectedSet should contain (value)
        count += 1
      }
      count should be (expectedSize)
    }
  }

  "A structure generator" should {
    "generate a structure for an atomic sequence of values" in {
      val space = Seq(false,true)
      val structure = MetaStructure.createStructure(space)
      structure mustBeIsomorphicTo space
      structure.nodes().size should be (1)
    }
    "generate a structure for all case class objects within a cartesian product of arguments" in {
      import Wolfe._
      case class Data(x:Boolean,y:Boolean)
      val space = Wolfe.all(Data)
      val structure = MetaStructure.createStructure(space)
      structure mustBeIsomorphicTo space
      structure.nodes().size should be (2)
    }
  }

}