using BenchmarkDotNet.Attributes;

namespace StructSpeedTest.Benchmarks;

/// <summary>
/// The same workloads as the quick runner, measured by BenchmarkDotNet.
/// The JMH counterpart is java/src/main/java/structspeed/jmh/StructBenchmarks.java.
/// Results are reported per call (one call processes N elements).
/// </summary>
[MemoryDiagnoser]
public class StructBenchmarks
{
    [Params(50_000, 1_000_000)]
    public int N;

    private Data[] _data = [];
    private Data[] _random = [];
    private Data[] _sortWork = [];
    private Data[] _radixTemp = [];
    private int[] _radixCounts = [];

    [GlobalSetup]
    public void Setup()
    {
        _data = new Data[N];
        _random = new Data[N];
        _sortWork = new Data[N];
        _radixTemp = new Data[N];
        _radixCounts = new int[HandOptimized.RadixCountsLength];
        Workloads.CreateRandom(_random);
    }

    [Benchmark] public long CreateZeroed() => Workloads.CreateZeroed(_data);
    [Benchmark] public long CreatePrng() => Workloads.CreateRandom(_data);
    [Benchmark] public long CreateGuid() => Workloads.CreateFromGuid(_data);
    [Benchmark] public long CreateCryptoBulk() => Workloads.CreateFromCryptoRng(_data);
    [Benchmark] public long EqualsScalar() => Workloads.CountMatches(_random);
    [Benchmark] public long EqualsSimd() => Workloads.CountMatchesSimd(_random);
    [Benchmark] public long Sort() => Workloads.CopyAndSort(_random, _sortWork);
    [Benchmark] public long HashSet() => Workloads.BuildHashSet(_random);
    [Benchmark] public long HandOptSortRadix() => HandOptimized.CopyAndRadixSort(_random, _sortWork, _radixTemp, _radixCounts);
    [Benchmark] public long HandOptHashSetOpen() => HandOptimized.BuildOpenHashSet(_random);
    [Benchmark] public long ParallelEquals() => Workloads.ParallelCountMatches(_random);
    [Benchmark] public long ParallelEqualsSimd() => Workloads.ParallelCountMatchesSimd(_random);
    [Benchmark] public long ParallelCreatePrng() => Workloads.ParallelCreateRandom(_data);
}
