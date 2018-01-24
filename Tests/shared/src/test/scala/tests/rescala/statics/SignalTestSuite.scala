package tests.rescala.statics

import java.util.concurrent.atomic.AtomicInteger

import rescala.infiltration.Infiltrator.assertLevel
import tests.rescala.util.RETests


class SignalTestSuite extends RETests {


  allEngines("handler Is Called When Change Occurs"){ engine => import engine._

    var test = 0
    val v1 = Var(1)
    val v2 = Var(2)

    val s1 = Signals.lift(v1, v2) { _ + _ }
    s1.changed += { (_) => test += 1 }

    assert(s1.now == 3)
    assert(test == 0)

    v2.set(3)
    assert(s1.now == 4)
    assert(test == 1)

    v2.set(3)
    assert(s1.now == 4)
    assert(test == 1)

  }


  allEngines("signal Reevaluates The Expression When Something It Depends On Is Updated"){ engine => import engine._
    val v = Var(0)
    var i = 1
    val s = Signal { v() + i }
    i = 2
    assert(s.now == 1)
    v.set(2)
    assert(s.now == 4)
  }

  allEngines("the Expression Is Not Evaluated Every Time now Is Called"){ engine => import engine._
    var a = 10
    val s = Signal(1 + 1 + a)
    assert(s.now === 12)
    a = 11
    assert(s.now === 12)
  }



  allEngines("level Is Correctly Computed"){ engine => import engine._

    val v = Var(1)

    val s1 = Signal { 2 * v() }
    val s2 = Signal { 3 * v() }
    val s3 = Signal { s1() + s2() }

    assertLevel(v, 0)
    assertLevel(s1, 1)
    assertLevel(s2, 1)
    assertLevel(s3, 2)


  }



  allEngines("dependant Is Only Invoked On Value Changes"){ engine => import engine._
    var changes = 0
    val v = Var(1)
    val s = Signal {
      changes += 1; v() + 1
    }
    assert(changes === 1)
    assert(s.now === 2)
    v.set(2)
    assert(s.now === 3)
    assert(changes === 2)
    v.set(2)
    assert(changes === 2) // is actually 3
  }







  allEngines("creating signals in signals based on changing signals"){ engine => import engine._
    val v0 = Var("level 0")
    val v3 = v0.map(_ + "level 1").map(_  + "level 2").map(_ + "level 3")

    val `dynamic signal changing from level 1 to level 5` = Signal {
      if (v0() == "level 0") v0() else {
        v3.map(_ + "level 4 inner").apply()
      }
    }
    assert(`dynamic signal changing from level 1 to level 5`.now == "level 0")
    //note: will start with level 5 because of static guess of current level done by the macro expansion
    assertLevel(`dynamic signal changing from level 1 to level 5`, 5)

    v0.set("level0+")
    assert(`dynamic signal changing from level 1 to level 5`.now == "level0+level 1level 2level 3level 4 inner")
    assertLevel(`dynamic signal changing from level 1 to level 5`, 5)
  }



  allEngines("signal Reevaluates The Expression"){ engine => import engine._
    val v = Var(0)
    var i = 1
    val s: Signal[Int] = v.map { _ => i }
    i = 2
    v.set(2)
    assert(s.now == 2)
  }

  allEngines("the Expression Is Note Evaluated Every Time Get Val Is Called"){ engine => import engine._
    var a = 10
    val s: Signal[Int] = Signals.static()(_ => 1 + 1 + a)
    assert(s.now === 12)
    a = 11
    assert(s.now === 12)
  }


  allEngines("simple Signal Returns Correct Expressions"){ engine => import engine._
    val s: Signal[Int] = Signals.static()(_ => 1 + 1 + 1)
    assert(s.now === 3)
  }

  allEngines("the Expression Is Evaluated Only Once"){ engine => import engine._

    var a = 0
    val v = Var(10)
    val s1: Signal[Int] = v.map { i =>
      a += 1
      i % 10
    }


    assert(a == 1)
    v.set(11)
    assert(a == 2)
    v.set(21)
    assert(a == 3)
    assert(s1.now === 1)
  }

  allEngines("handlers Are Executed"){ engine => import engine._

    val test = new AtomicInteger(0)
    val v = Var(1)

    val s1 = v.map { 2 * _ }
    val s2 = v.map { 3 * _ }
    val s3 = Signals.lift(s1, s2) { _ + _ }

    s1.changed += { (_) => test.incrementAndGet() }
    s2.changed += { (_) => test.incrementAndGet() }
    s3.changed += { (_) => test.incrementAndGet() }

    assert(test.get == 0)

    v.set(3)
    assert(test.get == 3)
  }

  allEngines("level Is Correctly Computed with combinators"){ engine => import engine._

    val v = Var(1)

    val s1 = v.map { 2 * _ }
    val s2 = v.map { 3 * _ }
    val s3 = Signals.lift(s1, s2) { _ + _ }

    assertLevel(v, 0)
    assertLevel(s1, 1)
    assertLevel(s2, 1)
    assertLevel(s3, 2)
  }


  allEngines("no Change Propagations"){ engine => import engine._
    val v = Var(1)
    val s = v.map(_ => 1)
    val s2 = Signal { s() }

    assert(s2.now === 1)
    assert(s.now === 1)

    v.set(2)
    assert(s.now === 1)
    assert(s2.now === 1)


    v.set(2)
    assert(s2.now === 1)
    assert(s.now === 1)


    v.set(3)
    assert(s2.now === 1)
    assert(s.now === 1)


  }

}
