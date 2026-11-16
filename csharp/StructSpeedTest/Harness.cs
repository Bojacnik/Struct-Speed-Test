using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace StructSpeedTest;

/// <summary>
/// Minimal benchmark harness. java/src/main/java/structspeed/Harness.java implements the same rules:
/// warm up until at least 1 s and 30 rounds have run (or 4 s and 3 rounds for slow cases), then time up to
/// 50 rounds (at least 5, stopping after 3 s) and report the median. Allocations and GCs are measured
/// across the timed rounds.
/// </summary>
public sealed class Harness(string runtime, bool quick)
{
    private readonly double _minWarmupSec = quick ? 0.5 : 1.0;
    private readonly double _maxWarmupSec = quick ? 2.0 : 4.0;
    private readonly int _maxRounds = quick ? 20 : 50;
    private readonly double _maxMeasureSec = quick ? 1.5 : 3.0;
    private const int MinWarmupRounds = 30;
    private const int MinSlowWarmupRounds = 3;
    private const int MinRounds = 5;

    private readonly StringBuilder _csv = new(
        "lang,runtime,size,case,median_ms,best_ms,ns_per_op,alloc_bytes_per_op,gcs_per_round,gc_pause_ms_per_round,bytes_per_element\n");

    public long Sink { get; private set; }

    public void PrintSizeHeader(int size)
    {
        Console.WriteLine();
        Console.WriteLine($"-- {size:N0} elements");
        Console.WriteLine($"{"Case",-24} {"median ms",11} {"ns/op",9} {"alloc B/op",11} {"GCs/round",10} {"GC ms/round",12}");
    }

    public void Run(string name, int size, Func<long> body)
    {
        var warmup = Stopwatch.StartNew();
        for (var round = 1; ; round++)
        {
            Sink += body();
            var elapsed = warmup.Elapsed.TotalSeconds;
            if (elapsed >= _minWarmupSec && round >= MinWarmupRounds) break;
            if (elapsed >= _maxWarmupSec && round >= MinSlowWarmupRounds) break;
        }

        var times = new List<double>(_maxRounds);
        var allocBefore = GC.GetTotalAllocatedBytes(precise: true);
        var gcsBefore = GC.CollectionCount(0);
        var pauseBefore = GC.GetTotalPauseDuration();
        var measure = Stopwatch.StartNew();
        while (times.Count < _maxRounds && !(times.Count >= MinRounds && measure.Elapsed.TotalSeconds >= _maxMeasureSec))
        {
            var start = Stopwatch.GetTimestamp();
            Sink += body();
            times.Add((Stopwatch.GetTimestamp() - start) * 1e9 / Stopwatch.Frequency);
        }
        var allocated = GC.GetTotalAllocatedBytes(precise: true) - allocBefore;
        var gcs = GC.CollectionCount(0) - gcsBefore;
        var pauseMs = (GC.GetTotalPauseDuration() - pauseBefore).TotalMilliseconds;

        times.Sort();
        var rounds = times.Count;
        var median = times[rounds / 2];
        var allocPerOp = allocated / ((double)rounds * size);
        var gcsPerRound = gcs / (double)rounds;
        var pausePerRound = pauseMs / rounds;

        Console.WriteLine($"{name,-24} {median / 1e6,11:F3} {median / size,9:F2} {allocPerOp,11:F1} {gcsPerRound,10:F2} {pausePerRound,12:F3}");
        AppendCsv(size, name, median / 1e6, times[0] / 1e6, median / size, allocPerOp, gcsPerRound, pausePerRound, null);
    }

    /// <summary>Heap bytes retained per element by a filled array of <paramref name="size"/> elements.</summary>
    public void Footprint(int size)
    {
        var before = SettledHeap();
        var data = new Data[size];
        Workloads.CreateRandom(data);
        var after = SettledHeap();
        GC.KeepAlive(data);

        var perElement = (after - before) / (double)size;
        Console.WriteLine();
        Console.WriteLine($"Memory footprint: {perElement:F1} bytes per element (array of {size:N0} filled elements)");
        AppendCsv(size, "memory.footprint", null, null, null, null, null, null, perElement);
    }

    public void WriteCsv(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        File.WriteAllText(path, _csv.ToString());
    }

    private static long SettledHeap()
    {
        for (var i = 0; i < 3; i++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
        return GC.GetTotalMemory(forceFullCollection: true);
    }

    private void AppendCsv(int size, string name, params double?[] values)
    {
        _csv.Append($"csharp,{runtime},{size},{name}");
        foreach (var value in values)
            _csv.Append(',').Append(value?.ToString("G6", CultureInfo.InvariantCulture));
        _csv.Append('\n');
    }
}
