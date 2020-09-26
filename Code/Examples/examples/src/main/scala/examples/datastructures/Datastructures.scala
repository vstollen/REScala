package examples.datastructures

import rescala.default._

import scala.collection.immutable.Queue

/**
  * A mutable Queue working with Signals
  * (wraps scala.collection.immutable.Queue)
  */
class SQueue[T] {
  val _queue: Var[Queue[T]] = Var(Queue[T]())

  // some signals
  lazy val head = Signal {
    _queue().headOption
  }
  lazy val length  = Signal { _queue().length }
  lazy val isEmpty = Signal { _queue().isEmpty }

  // methods mutating the state of the SQueue
  def enqueue(elem: T) = _queue set _queue.now.enqueue(elem)
  def dequeue(): T = {
    val (first, tail): (T, Queue[T]) = _queue.now.dequeue
    _queue set tail
    first
  }
}

object SQueue {
  def apply[T](xs: T*): SQueue[T] = {
    val sq: SQueue[T] = new SQueue[T]
    sq._queue set sq._queue.now.enqueueAll(xs.toList)
    sq
  }
}

class SStack[T] {
  val _stack = Var(List[T]())

  // some signals
  lazy val top     = Signal { _stack().headOption }
  lazy val length  = Signal { _stack().size }
  lazy val isEmpty = Signal { _stack().isEmpty }

  // methods mutating the state of the SQueue
  def push(elem: T): Unit = _stack.transform(elem :: _)
  def pop(): T = {
    val out :: rest = _stack.now: @scala.annotation.nowarn
    _stack.set(rest)
    out
  }
}

object SStack {
  def apply[T](xs: T*): SStack[T] = {
    val ss = new SStack[T]
    ss._stack.transform(xs.toList ::: _)
    ss
  }
}
