package rescala.core.reactor

import rescala.core.{Interp, ReName}
import rescala.operator.Observe.ObserveInteract
import rescala.operator.RExceptions.ObservedException
import rescala.operator.{Observe, Pulse}
import tests.rescala.testtools.RETests

class ReactorWithoutAPITest extends RETests {

  import rescala.default._


  class CustomReactorReactive(
                             initState: ReStructure#State[ReactorStage, ReStructure],
                           ) extends Derived
    with Interp[ReactorStage, ReStructure] {
    override type Value = ReactorStage
    override protected[rescala] def state: State               = initState
    override protected[rescala] def name: ReName               = "I am a name"
    override protected[rescala] def commit(base: Value): Value = base

    override protected[rescala] def reevaluate(input: ReIn): Rout = {
      val currentStage = input.before
      val requiredEvents = currentStage.waitingFor
      val res = input.depend(requiredEvents)
      val nextStage = currentStage.computeNext(res)
      input.withValue(nextStage)
    }

    override def interpret(v: Value): String = v
  }

  object CustomReactor {
    def once(body: ReactorStage => Unit): CustomReactor = {
      body(new ReactorStage)
    }
  }

  class CustomReactor(body: ReactorStage => Unit) {

    private def execOnce(): Unit = {
      // Execute on new thread, which can be suspended in case of next()?
      body(new ReactorStage)
    }


  }

  class ReactorStage {
    def next[A](event: Evt[A])(callback: A => Unit): ReactorStage = {
      Observe.strong(event, fireImmediately = false) { evtVal =>
        val internalVal = event.internalAccess(evtVal)
        new ObserveInteract {
          override def checkExceptionAndRemoval(): Boolean = {
            evtVal match {
              case Pulse.Exceptional(f) =>
                throw ObservedException(event, "observed", f)
              case _ => ()
            }
            false
          }

          override def execute(): Unit =
            internalVal match {
              case Pulse.NoChange => ()
              case Pulse.Value(v) => println("Continue body execution") // Continue body thread?
              case Pulse.Exceptional(f) => () // Error handling?
            }
        }
      }

    }
  }

  test("Reactor waits for event when using next") {
    val state = Var(0)
    val e1 = Evt[Unit]()

    assert(state.now === 0)

    CustomReactor.once { self =>
      state.set(1)
      self.next(e1) { res =>
        state.set(2)
      }
    }

    assert(state.now == 1)
    e1.fire()
    assert(state.now == 2)
  }
}
