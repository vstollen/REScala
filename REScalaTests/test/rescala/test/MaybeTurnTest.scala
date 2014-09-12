package rescala.test

import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar

import rescala.propagation._

class MaybeTurnTest extends AssertionsForJUnit with MockitoSugar {

  @Test def noneDynamicNoImplicit() = Turn.currentTurn.withValue(None) {
    assert(implicitly[MaybeTurn] === MaybeTurn(None))
  }

  @Test def someDynamicNoImplicit() = Turn.newTurn { (dynamicTurn: Turn) =>
    assert(implicitly[MaybeTurn] === MaybeTurn(Some(dynamicTurn)))
  }

  @Test def noneDynamicSomeImplicit() = Turn.currentTurn.withValue(None) {
    implicit val implicitTurn: Turn = new Turn
    assert(implicitly[MaybeTurn] === MaybeTurn(Some(implicitTurn)))
  }

  @Test def someDynamicSomeImplicit() = Turn.newTurn { (dynamicTurn: Turn) =>
    implicit val implicitTurn: Turn = new Turn
    assert(implicitly[MaybeTurn] === MaybeTurn(Some(implicitTurn)))
  }

}
