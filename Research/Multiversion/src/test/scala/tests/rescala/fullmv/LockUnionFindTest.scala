package tests.rescala.fullmv

import org.scalatest.FunSuite
import rescala.fullmv.sgt.synchronization.SubsumableLock.TryLockResult
import rescala.fullmv.sgt.synchronization.SubsumableLockImpl
import rescala.testhelper.Spawn

import scala.concurrent.TimeoutException
import scala.util.{Failure, Try}

class LockUnionFindTest extends FunSuite {
  test("tryLock works"){
    // we can lock
    val a = new SubsumableLockImpl()
    assert(a.tryLock() === TryLockResult(success = true, a, a.gUID))

    // lock is exclusive
    assert(a.tryLock() === TryLockResult(success = false, a, a.gUID))

    // unlock works
    a.unlock()
    assert(a.tryLock() === TryLockResult(success = true, a, a.gUID))
  }

  test("lock works") {
    // we can lock
    val a = new SubsumableLockImpl()
    assert(Spawn{a.lock()}.join(101) === TryLockResult(success = true, a, a.gUID))

    // lock is exclusive
    assert(a.tryLock() === TryLockResult(success = false, a, a.gUID))
    // and blocks..
    val blockedB = Spawn{a.lock()}
    intercept[TimeoutException] {
      blockedB.join(103)
    }

    // unlock unblocks
    a.unlock()
    assert(blockedB.join(104) === TryLockResult(success = true, a, a.gUID))
  }

  test("union works") {
    val a = new SubsumableLockImpl
    val b = new SubsumableLockImpl

    assert(a.tryLock() === TryLockResult(success = true, a, a.gUID))
    val resB = b.tryLock()
    assert(resB === TryLockResult(success = true, b, b.gUID))

    a.subsume(resB)

    assert(a.getLockedRoot.contains(b.gUID))
    assert(b.getLockedRoot.contains(b.gUID))

    b.unlock()

    assert(a.tryLock() === TryLockResult(success = true, b, b.gUID))
    assert(a.tryLock() === TryLockResult(success = false, b, b.gUID))
    assert(b.tryLock() === TryLockResult(success = false, b, b.gUID))

    b.unlock()

    assert(b.tryLock() === TryLockResult(success = true, b, b.gUID))
    assert(b.tryLock() === TryLockResult(success = false, b, b.gUID))
    assert(a.tryLock() === TryLockResult(success = false, b, b.gUID))
  }

  test("subsume correctly wakes all threads") {
    val a, b = new SubsumableLockImpl()
    assert(a.tryLock().success)
    val resB = b.tryLock()
    assert(resB.success)

    var counter = 0
    def spawnIncrementUnderLockThread(lock: SubsumableLockImpl) = {
      Spawn {
        val res = lock.lock()
        assert(res.success)
        val c = counter
        counter += 1
        res.newParent.unlock()
        c -> res.globalRoot
      }
    }

    val queued = List.fill(5){ spawnIncrementUnderLockThread(a) } ++ List.fill(5){ spawnIncrementUnderLockThread(b) }

    val timeout = System.currentTimeMillis() + 50
    val timeouts = queued.map { thread => Try { thread.join(timeout - System.currentTimeMillis()) } }
    assert(!timeouts.exists{
      case Failure(_: TimeoutException) => false
      case _ => true
    }, s"All threads should have timed out, but some did not.")

    a.subsume(resB)
    resB.newParent.unlock()

    assert(queued.map(_.join(50)).toSet === (0 until 10).map(_ -> resB.globalRoot).toSet)
  }
}
