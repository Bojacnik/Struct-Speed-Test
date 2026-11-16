package structspeed.jmh;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import structspeed.Data;
import structspeed.Workloads;

/**
 * The same workloads as the quick runner, measured by JMH.
 * The BenchmarkDotNet counterpart is csharp/StructSpeedTest.Benchmarks/StructBenchmarks.cs.
 * Results are reported per call (one call processes N elements). Add "-prof gc" for allocation numbers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseParallelGC", "-Xms4g", "-Xmx4g"})
public class StructBenchmarks {

    @Param({"50000", "1000000"})
    public int n;

    private Data[] data;
    private Data[] random;
    private Data[] sortWork;
    private byte[] randomBytes;

    @Setup
    public void setup() {
        data = new Data[n];
        random = new Data[n];
        sortWork = new Data[n];
        randomBytes = new byte[n * 16];
        Workloads.createRandom(random);
    }

    @Benchmark public long createZeroed() { return Workloads.createZeroed(data); }
    @Benchmark public long createPrng() { return Workloads.createRandom(data); }
    @Benchmark public long createGuid() { return Workloads.createFromUuid(data); }
    @Benchmark public long createCryptoBulk() { return Workloads.createFromCryptoRng(data, randomBytes); }
    @Benchmark public long equalsScalar() { return Workloads.countMatches(random); }
    @Benchmark public long sort() { return Workloads.copyAndSort(random, sortWork); }
    @Benchmark public long hashSet() { return Workloads.buildHashSet(random); }
    @Benchmark public long parallelEquals() { return Workloads.parallelCountMatches(random); }
    @Benchmark public long parallelCreatePrng() { return Workloads.parallelCreateRandom(data); }
}
