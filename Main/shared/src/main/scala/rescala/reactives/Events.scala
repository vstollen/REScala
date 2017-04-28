package rescala.reactives

import rescala.engine.{Transient, Turn, TurnSource}
import rescala.graph.Pulse.NoChange
import rescala.graph.{Base, Disconnectable, DynamicTicket, Pulse, Reactive, ReevaluationResult, StaticTicket, Struct}
import rescala.reactives.Signals.Diff

object Events {

  private abstract class StaticEvent[T, S <: Struct](_bud: S#State[Pulse[T], S], expr: StaticTicket[S] => Pulse[T], override val toString: String)
    extends Base[T, S](_bud) with Event[T, S] {
    override protected[rescala] def reevaluate(turn: Turn[S]): ReevaluationResult[Value, S] = ReevaluationResult.Static(Pulse.tryCatch(expr(turn.static()), onEmpty = NoChange))
  }

  private abstract class ChangeEvent[T, S <: Struct](_bud: S#State[Pulse[Diff[T]], S], signal: Signal[T, S])
    extends Base[Diff[T], S](_bud) with Event[Diff[T], S] {
    override protected[rescala] def reevaluate(turn: Turn[S]): ReevaluationResult[Value, S] = {
      val pulse = {
        val from = turn.before(signal)
        val to = turn.after(signal)
        if (from != Pulse.empty && from != to) Pulse.Value(Diff(from, to))
        else Pulse.NoChange
      }
      ReevaluationResult.Static(pulse)
    }
  }

  private abstract class DynamicEvent[T, S <: Struct](_bud: S#State[Pulse[T], S], expr: DynamicTicket[S] => Pulse[T]) extends Base[T, S](_bud) with Event[T, S] {

    override protected[rescala] def reevaluate(turn: Turn[S]): ReevaluationResult[Pulse[T], S] = {
      val dt = turn.dynamic()
      val newPulse = Pulse.tryCatch(expr(dt), onEmpty = NoChange)
      ReevaluationResult.Dynamic(newPulse, dt.collectedDependencies)
    }
  }

  /** the basic method to create static events */
  def static[T, S <: Struct](name: String, dependencies: Reactive[S]*)(calculate: StaticTicket[S] => Pulse[T])(implicit maybe: TurnSource[S]): Event[T, S] = maybe { initTurn =>
    initTurn.create[Pulse[T], Event[T, S]](dependencies.toSet, dynamic = false, Transient) {
      new StaticEvent[T, S](_, calculate, name) with Disconnectable[S]
    }
  }

  /** create dynamic events */
  def dynamic[T, S <: Struct](dependencies: Reactive[S]*)(expr: DynamicTicket[S] => Option[T])(implicit maybe: TurnSource[S]): Event[T, S] = {
    maybe { initialTurn =>
      initialTurn.create[Pulse[T], Event[T, S]](dependencies.toSet, dynamic = true, Transient) {
        new DynamicEvent[T, S](_, expr.andThen(Pulse.fromOption)) with Disconnectable[S]
      }
    }
  }

  def change[A, S <: Struct](signal: Signal[A, S])(implicit maybe: TurnSource[S]): Event[Diff[A], S] = maybe { initTurn =>
    val dependencySet: Set[Reactive[S]] = Set(signal)
    initTurn.create[Pulse[Diff[A]], Event[Diff[A], S]](Set(signal), dynamic = false, Transient) {
      new ChangeEvent[A, S](_, signal) with Disconnectable[S]
    }
  }
}
