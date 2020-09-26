package rescala.benchmarks.distributed.rtt

import java.util.concurrent._

import org.openjdk.jmh.annotations._
import rescala.core.REName
import rescala.fullmv.mirrors.localcloning.{FakeDelayer, ReactiveLocalClone}
import rescala.fullmv.{FullMVEngine, FullMVStruct}
import rescala.reactives.{Signal, Var}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 5000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 5000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
class Remerge {
  @Param(Array("5"))
  var totalLength: Int = _

  @Param(Array("2"))
  var threads: Int = _

  @Param(Array("1", "2", "3", "5"))
  var mergeAt: Int = _

  var sources: Seq[(FullMVEngine, Var[Int, FullMVStruct])]                  = _
  var instantMergeHost: FullMVEngine                                        = _
  var remotesOnInstantMerge: Seq[Signal[Int, FullMVStruct]]                 = _
  var instantMerge: Signal[Int, FullMVStruct]                               = _
  var preMergeDistance: Seq[Seq[(FullMVEngine, Signal[Int, FullMVStruct])]] = _
  var mergeHost: FullMVEngine                                               = _
  var remotesOnMerge: Seq[Signal[Int, FullMVStruct]]                        = _
  var merge: Signal[Int, FullMVStruct]                                      = _
  var postMergeDistance: Seq[(FullMVEngine, Signal[Int, FullMVStruct])]     = _

  var barrier: CyclicBarrier      = _
  var threadpool: ExecutorService = _

  @Param(Array("50"))
  var msDelay: Int = _

  @Setup(Level.Iteration)
  def setup(): Unit = {
    FakeDelayer.enable()

    barrier = new CyclicBarrier(threads)
    threadpool = Executors.newFixedThreadPool(threads)

    sources = for (i <- 1 to threads) yield {
      val engine = new FullMVEngine(10.seconds, s"src-$i")
      engine -> {
        import engine._
        engine.Var(0)
      }
    }

    instantMergeHost = new FullMVEngine(10.seconds, "merge")
    remotesOnInstantMerge = for (i <- 1 to threads) yield {
      REName.named(s"clone-merge-$i") { implicit ! =>
        ReactiveLocalClone(sources(i - 1)._2, instantMergeHost, msDelay.millis)
      }
    }
    instantMerge = {
      val e = instantMergeHost
      import e._
      Signals.static(remotesOnInstantMerge: _*) { t =>
        remotesOnInstantMerge.map(t.dependStatic).sum
      }
    }

    var preMerge: Seq[(FullMVEngine, Signal[Int, FullMVStruct])] = sources
    preMergeDistance = for (d <- 1 until mergeAt) yield {
      preMerge = for (i <- 1 to threads) yield {
        val host = new FullMVEngine(10.seconds, s"premerge-$d-$i")
        host -> REName.named(s"clone-premerge-$d-$i") { implicit ! =>
          ReactiveLocalClone(preMerge(i - 1)._2, host, msDelay.millis)
        }
      }
      preMerge
    }

    mergeHost = new FullMVEngine(10.seconds, "merge")
    remotesOnMerge = for (i <- 1 to threads) yield {
      REName.named(s"clone-merge-$i") { implicit ! =>
        ReactiveLocalClone(preMerge(i - 1)._2, mergeHost, msDelay.millis)
      }
    }
    merge = {
      val e = mergeHost
      import e._
      Signals.static(remotesOnMerge: _*) { t =>
        remotesOnMerge.map(t.dependStatic).sum
      }
    }

    var postMerge: (FullMVEngine, Signal[Int, FullMVStruct]) = mergeHost -> merge
    postMergeDistance = for (i <- 1 to totalLength - mergeAt) yield {
      val host = new FullMVEngine(10.seconds, s"host-$i")
      postMerge = host -> REName.named(s"clone-$i") { implicit ! =>
        ReactiveLocalClone(postMerge._2, host, msDelay.millis)
      }
      postMerge
    }

  }

  @TearDown(Level.Iteration)
  def teardown(): Unit = {
    FakeDelayer.shutdown()
    threadpool.shutdown()
  }

  @Benchmark
  def run(): Unit = {
    val results = for (i <- 1 to threads) yield {
      val p = Promise[Unit]()
      threadpool.submit(new Runnable() {
        override def run(): Unit = {
          p.complete(Try {
            val (engine, source) = sources(i - 1)
            engine.transactionWithWrapup(source)({ ticket =>
              val before = ticket.now(source)
              source.admit(before + 1)(ticket)
            })({ (_, ticket) =>
              // prevent turns from completing before all turns have updated the whole graph
              barrier.await()
            })
          })
        }
      })
      p.future
    }
    results.foreach(Await.result(_, Duration.Inf))
  }
}
