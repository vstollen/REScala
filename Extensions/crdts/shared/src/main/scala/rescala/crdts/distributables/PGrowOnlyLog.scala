package rescala.crdts.distributables

import rescala.crdts.distributables.DistributedSignal.PVarFactory
import rescala.crdts.statecrdts.sequences.{RGOA, Vertex}
import rescala.default._

case class PGrowOnlyLog[A](initial: RGOA[A] = RGOA[A]())
extends DistributedSignal[List[A], RGOA[A]](initial)(RGOA.crdt[A]) {

  def append(a: A): Unit = {
    crdtSignal.transform(_.append(Vertex(a)))
  }
  def prepend(a: A): Unit = {
    crdtSignal.transform(_.prepend(Vertex(a)))
  }

  def contains(a: A): Boolean = crdtSignal.readValueOnce.containsValue(a)

  // allows the log to log events of type a and append them to the log
  def observe(e: Event[A]): Unit = e.observe(a => append(a))
}

object PGrowOnlyLog {
  /**
    * Allows creation of PVertexLogs by passing a set of initial values.
    */
  def apply[A](values: List[A]): PGrowOnlyLog[A] = new PGrowOnlyLog[A](RGOA(values))

  //noinspection ConvertExpressionToSAM
  implicit def PGrowOnlyLogFactory[A]: PVarFactory[PGrowOnlyLog[A]] =
    new PVarFactory[PGrowOnlyLog[A]] {
      override def apply(): PGrowOnlyLog[A] = PGrowOnlyLog[A]()
    }
}
