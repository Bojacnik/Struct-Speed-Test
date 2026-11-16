using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace TestingSpeed;

/// <summary>
/// Struct creation / comparison benchmark.
/// The Java counterpart in java/StructSpeedTest.java runs the same cases with the same harness,
/// so the numbers can be compared side by side.
/// </summary>
internal static class Program
{
    private const int DefaultCount = 50_000;
    private const int MinWarmupRounds = 30;
    private const long MinWarmupMillis = 1_000; // give the tiered JIT time to reach fully optimized code
    private const int MeasuredRounds = 50;

    private static long s_sink;

    public static void Main(string[] args)
    {
        var count = args.Length > 0 ? int.Parse(args[0]) : DefaultCount;
        var data = new Data[count];

        Console.WriteLine(
            $"C# .NET {Environment.Version} | {RuntimeInformation.OSArchitecture} | {Environment.ProcessorCount} cores | " +
            $"{count:N0} elements | warmup >= {MinWarmupRounds} rounds & {MinWarmupMillis} ms | {MeasuredRounds} measured rounds");
        Console.WriteLine($"{"Benchmark",-34} {"median ms",10} {"best ms",10} {"ns/op",8}");

        Run("Create zeroed", count, () => CreateZeroed(data));
        Run("Create from PRNG (SplitMix64)", count, () => CreateRandom(data, 42));
        Run("Create from Guid.NewGuid()", count, () => CreateFromGuid(data));
        Run("Create from crypto RNG (bulk)", count, () => CreateFromCryptoRng(data));

        CreateRandom(data, 42); // deterministic contents for the comparison cases
        Run("Equals: first vs all", count, () => CountMatches(data));
        Run("Equals: first vs all (SIMD)", count, () => Data.CountMatches(data, data[0]));

        Console.WriteLine($"checksum: {s_sink}");
    }

    private static long CreateZeroed(Data[] data)
    {
        for (var i = 0; i < data.Length; i++)
            data[i] = new Data(0, 0);
        return data.Length;
    }

    private static long CreateRandom(Data[] data, ulong seed)
    {
        var state = seed;
        for (var i = 0; i < data.Length; i++)
            data[i] = new Data(SplitMix64(ref state), SplitMix64(ref state));
        return (long)state;
    }

    private static long CreateFromGuid(Data[] data)
    {
        for (var i = 0; i < data.Length; i++)
            data[i] = new Data(Guid.NewGuid());
        return data[^1].GetHashCode();
    }

    // The struct array is contiguous memory, so the OS RNG fills every element in one call.
    private static long CreateFromCryptoRng(Data[] data)
    {
        RandomNumberGenerator.Fill(MemoryMarshal.AsBytes(data.AsSpan()));
        return data[^1].GetHashCode();
    }

    private static long CountMatches(Data[] data)
    {
        var first = data[0];
        var matches = 0;
        foreach (ref readonly var item in data.AsSpan())
            matches += first.Equals(item) ? 1 : 0;
        return matches;
    }

    private static ulong SplitMix64(ref ulong state)
    {
        var z = state += 0x9E3779B97F4A7C15UL;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9UL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBUL;
        return z ^ (z >> 31);
    }

    private static void Run(string name, int count, Func<long> body)
    {
        var warmup = Stopwatch.StartNew();
        for (var round = 0; round < MinWarmupRounds || warmup.ElapsedMilliseconds < MinWarmupMillis; round++)
            s_sink += body();

        var timesNs = new double[MeasuredRounds];
        for (var round = 0; round < MeasuredRounds; round++)
        {
            var start = Stopwatch.GetTimestamp();
            s_sink += body();
            timesNs[round] = (Stopwatch.GetTimestamp() - start) * 1e9 / Stopwatch.Frequency;
        }

        Array.Sort(timesNs);
        var median = timesNs[MeasuredRounds / 2];
        Console.WriteLine($"{name,-34} {median / 1e6,10:F4} {timesNs[0] / 1e6,10:F4} {median / count,8:F2}");
    }
}
