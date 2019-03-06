package rescala.crdts.statecrdts.sequences

import rescala.crdts.statecrdts.StateCRDT
import rescala.crdts.statecrdts.sets.TwoPSet

import scala.collection.immutable.HashMap

/**
  * Replicated Growable Array
  *
  * @param payload The payload consist of one TwoPhase Set which stores the vertices, one HashMap which stores the edges
  *                between vertices.
  * @tparam A The type of the elements stored in this array
  */
case class RGA[A](vertices: TwoPSet[Vertex],
                  edges: Map[Vertex, Vertex],
                  values: Map[Vertex, A])
  extends CRDTSequence[A] {

  override type PayloadT = (TwoPSet[Vertex], Map[Vertex, Vertex], Map[Vertex, A])
  override def fromPayload(payload: PayloadT): RGA[A] = RGA[A](payload._1, payload._2, payload._3)
  override type SelfT = RGA[A]
  def remove(v: Vertex): SelfT = copy(vertices = vertices.remove(v))
}


object RGA {
  type Payload[A] = (TwoPSet[Vertex], HashMap[Vertex, Vertex])


  def apply[A](values: Seq[A]): RGA[A] = {
    values.reverse.foldLeft(empty[A]) {
      case (r, a) => r.addRight(Vertex.start, a)
    }
  }

  def empty[A]: RGA[A] = new RGA[A](TwoPSet[Vertex](), Map(Vertex.start -> Vertex.end), Map())


  implicit def RGA2CRDTInstance[A]: StateCRDT[List[A], RGA[A]] =
    new StateCRDT[List[A], RGA[A]] {
      override def value(target: RGA[A]): List[A] = target.value

      override def merge(left: RGA[A], r: RGA[A]): RGA[A] = {
        val newVertices = r.vertexIterator.toList.filter(!left.edges.contains(_))

        // build map of old insertion positions of the new vertices
        val oldPositions = r.edges.foldLeft(Map(): Map[Vertex, Vertex]) {
          case (m, (u, v)) => if (newVertices.contains(v)) m + (v -> u) else m
        }

        newVertices.foldLeft(left) { case (merged: RGA[A], v: Vertex) =>
            merged.addRight(oldPositions(v), v, left.values(v))
        }
      }
    }
}

