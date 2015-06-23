package rescala.synchronization

import rescala.graph.JVMFactories.ParRPState
import rescala.graph.{JVMFactories, Reactive}
import rescala.propagation.{LevelQueue, PropagationImpl}
import rescala.synchronization.ParRP.{Await, Done, Retry}
import rescala.turns.Turn

import scala.annotation.tailrec

class ParRP(var backOff: Int) extends FactoryReference[ParRPState](JVMFactories.parrp) with PropagationImpl[ParRPState] {

  type S = ParRPState

  override def toString: String = s"ParRP(${key.id})"

  final val key: Key = new Key(this)

  /**
   * creating a signal causes some unpredictable reactives to be used inside the turn.
   * these will have their locks be acquired dynamically see below for how that works.
   * the newly created reactive on the other hand can not be locked by anything, so we just grab the lock
   * (we do need to grab it, so it can be transferred to some other waiting transaction).
   * it is important, that the locks for the dependencies are acquired BEFORE the constructor for the new reactive.
   * is executed, because the constructor typically accesses the dependencies to create its initial value.
   */
  override def create[T <: Reactive[S]](dependencies: Set[Reactive[S]], dynamic: Boolean)(f: => T): T = {
    dependencies.foreach(accessDynamic)
    val reactive = f
    val owner = reactive.lock.tryLock(key)
    assert(owner eq key, s"$this failed to acquire lock on newly created reactive $reactive")
    super.create(dependencies, dynamic)(reactive)
  }

  /** this is called after the turn has finished propagating, but before handlers are executed */
  override def releasePhase(): Unit = key.releaseAll()

  /** allow turn to handle dynamic access to reactives */
  override def accessDynamic(dependency: Reactive[S]): Unit = acquireShared(dependency)


  var currentBackOff = backOff

  override def lockPhase(initialWrites: List[Reactive[S]]): Unit = lockReachable(initialWrites, acquireWrite)

  def acquireWrite(reactive: Reactive[S]): Boolean =
    if (reactive.lock.tryLock(key) eq key) true
    else {
      key.lockKeychain {
        key.releaseAll()
        key.keychain = new Keychain(key)
      }
      if (currentBackOff == 0) {
        acquireShared(reactive)
        backOff /= 2
        currentBackOff = backOff
      }
      else if (currentBackOff > 0) {
        currentBackOff -= 1
      }
      false
    }

  /** lock all reactives reachable from the initial sources
    * retry when acquire returns false */
  def lockReachable(initial: List[Reactive[S]], acquire: Reactive[S] => Boolean)(implicit turn: Turn[S]): Unit = {
    val lq = new LevelQueue()
    initial.foreach(lq.enqueue(-42))

    lq.evaluateQueue { reactive =>
      if (acquire(reactive))
        reactive.outgoing.get.foreach(lq.enqueue(-42))
      else {
        lq.clear()
        initial.foreach(lq.enqueue(-42))
      }
    }
  }

  /** registering a dependency on a node we do not personally own does require some additional care.
    * we let the other turn update the dependency and admit the dependent into the propagation queue
    * so that it gets updated when that turn continues
    * the responsibility for correctly passing the locks is moved to the commit phase */
  override def register(sink: Reactive[S])(source: Reactive[S]): Unit = {
    val owner = acquireShared(source)
    if (owner ne key) {
      if (!source.outgoing.get.contains(sink)) {
        owner.turn.register(sink)(source)
        owner.turn.admit(sink)
        key.lockKeychain {
          assert(key.keychain == owner.keychain, "tried to transfer locks between keychains")
          key.keychain.addFallthrough(owner)
        }
      }
    }
    else {
      super.register(sink)(source)
    }
  }

  /** this is for cases where we register and then unregister the same dependency in a single turn */
  override def unregister(sink: Reactive[S])(source: Reactive[S]): Unit = {
    val owner = acquireShared(source)
    if (owner ne key) {
      owner.turn.unregister(sink)(source)
      key.lockKeychain(key.keychain.removeFallthrough(owner))
      if (!sink.incoming(this).exists(_.lock.isOwner(owner))) owner.turn.forget(sink)
    }
    else super.unregister(sink)(source)
  }

  def acquireShared(reactive: Reactive[S]): Key = acquireShared(reactive.lock, key)

  @tailrec
  private def acquireShared(lock: TurnLock, requester: Key): Key = {
    val oldOwner = lock.tryLock(requester)

    val res =
      if (oldOwner eq requester) Done(requester)
      else {
        Keychains.lockKeychains(requester, oldOwner) {
          // be aware that the owner of the lock could change at any time.
          // but it can not change when the owner is the requester or old owner,
          // because the keychain protects unlocking.
          lock.tryLock(requester) match {
            // make sure the other owner did not unlock before we got his master lock
            case owner if owner eq requester => Done(requester)
            case owner if owner ne oldOwner => Retry
            case owner if requester.keychain eq owner.keychain => Done(owner)
            case owner => // owner here must be equal to the oldOwner, whose keychain is locked
              lock.share(requester)
              owner.keychain.append(requester.keychain)
              Await
          }
        }
      }
    res match {
      case Await =>
        requester.await()
        lock.acquired(requester)
        requester
      case Retry => acquireShared(lock, requester)
      case Done(o) => o
    }
  }

}

private object ParRP {

  sealed trait Result[+R]
  object Await extends Result[Nothing]
  object Retry extends Result[Nothing]
  case class Done[R](r: R) extends Result[R]

}
