package benchmarks.simple

import java.util.concurrent.TimeUnit

import benchmarks.{Size, EngineParam, Workload}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.BenchmarkParams
import rescala.turns.{Engine, Turn}
import rescala.{Event, Evt, Signal, Var}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
class Creation[S <: rescala.graph.Spores] {

  implicit var engine: Engine[S, Turn[S]] = _

  @Setup
  def setup(params: BenchmarkParams, work: Workload, engineParam: EngineParam[S]) = {
    engine = engineParam.engine
  }

  @Benchmark
  def `var`(): Var[String, S] = {
    engine.Var("")
  }

  @Benchmark
  def `evt`(): Evt[String, S] = {
    engine.Evt[String]()
  }

  @Benchmark
  def `var and derived signal`(): Signal[String, S] = {
    val v1 = engine.Var("")
    v1.map(identity)
  }

  @Benchmark
  def `evt and derived event`(): Event[String, S] = {
    val e1 = engine.Evt[String]()
    e1.map(identity)
  }

  @Benchmark
  def `signal fanout`(size: Size): Seq[Signal[String, S]] = {
    val v1 = Var("")
    Range(0,size.size).map(_ => v1.map(identity))
  }

}
