package benchmarks.restoring

import java.util.concurrent.TimeUnit

import benchmarks.{EngineParam, Size, Step, Workload}
import org.openjdk.jmh.annotations._
import rescala.core.{Scheduler, Struct}
import rescala.interface.RescalaInterface
import rescala.levelbased.SimpleStruct
import rescala.reactives.{Evt, Var}
import rescala.restoration.ReStoringScheduler
import rescala.restoration.ReCirce._

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
class RestoringSimple[S <: Struct] {

  var engine: RescalaInterface[S] = _
  implicit def scheduler: Scheduler[S] = engine.scheduler


  var source: Evt[Int, S] = _
  var result: List[Any] = _

  @Param(Array("0", "0.2", "1"))
  var foldPercent: Float = _

  @Setup
  def setup(size: Size, engineParam: EngineParam[S]) = {
    engine = engineParam.engine
    source = engine.Evt[Int]()
    result = Nil
    if (size.size <= 0) result = List(source.map(_+1))
    val split = math.round(size.size * foldPercent)
    for (_ <- Range(0, split)) {
      result = source.count :: result
    }
    for (_ <- Range(split, size.size)) {
      result = source.map(_ + 1) :: result
    }
  }

  @Benchmark
  def countMany(step: Step): Unit = source.fire(step.run())


}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
class RestoringVar[S <: Struct] {

  var engine: RescalaInterface[S] = _
  implicit def scheduler: Scheduler[S] = engine.scheduler

  var sourceVar: Var[Int, S] = _

  @Setup
  def setup(engineParam: EngineParam[S]) = {
    engine = engineParam.engine
    sourceVar = engine.Var(-1)
  }

  @Benchmark
  def singleVar(step: Step): Unit = sourceVar set step.run()
}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
class RestoringSnapshotVsInitial {

  var snapshot: scala.collection.mutable.Map[String, String] = _

  val syncInterface: RescalaInterface[SimpleStruct] = RescalaInterface.interfaceFor(rescala.Schedulers.synchron)

  def build[S <: Struct](engine: RescalaInterface[S], size: Int) = {
    import engine.implicitScheduler
    val source = engine.Evt[Int]()
    val res = for (i <- 1 to size) yield {
      source.count.map(_+1).map(_+1)
    }
    (source, res)
  }

  @Setup
  def setup(size: Size) = {
    val engine = new ReStoringScheduler()
    val (source, res) = build(engine, size.size)
    source.fire(10)(engine)
    source.fire(20)(engine)
    snapshot = engine.snapshot()
  }

  @Benchmark
  def fresh(size: Size) = build(new ReStoringScheduler(), size.size)

  @Benchmark
  def restored(size: Size) = {
    val engine = new ReStoringScheduler(restoreFrom = snapshot)
    build(engine, size.size)
  }

  @Benchmark
  def noSnapshots(size: Size) = {
    build(syncInterface, size.size)
  }


}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
class RestoringSnapshotVsRecomputationA[S <: Struct] {

  var snapshot: scala.collection.mutable.Map[String, String] = _

  def build(implicit engine: ReStoringScheduler) = {
    val source = engine.Evt[Int]()
    val res = source.list().map(_.size)
    (source, res)
  }

  @Setup
  def setup(size: Size, workload: Workload) = {
    val engine = new ReStoringScheduler()
    val (source, res) = build(engine)
    for (i <- 1 to size.size) source.fire(i)(engine)
    snapshot = engine.snapshot()
  }

  @Benchmark
  def restored(size: Size) = {
    val engine = new ReStoringScheduler(restoreFrom = snapshot)
    build(engine)
  }
}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
class RestoringSnapshotVsRecomputationB[S <: Struct] {

  var snapshot: scala.collection.mutable.Map[String, String] = _

  def build(implicit engine: ReStoringScheduler) = {
    val source = engine.Evt[Int]()
    val res = source.count().map(List.tabulate(_)(identity))
    (source, res)
  }

  @Setup
  def setup(size: Size, workload: Workload) = {
    val engine = new ReStoringScheduler()
    val (source, res) = build(engine)
    for (i <- 1 to size.size) source.fire(i)(engine)
    snapshot = engine.snapshot()
  }

  @Benchmark
  def derived(size: Size) = {
    val engine = new ReStoringScheduler(restoreFrom = snapshot)
    build(engine)
  }
}
