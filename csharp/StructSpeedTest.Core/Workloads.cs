using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace StructSpeedTest;

/// <summary>
/// Every benchmark case as a plain static method, shared by the quick runner and BenchmarkDotNet.
/// java/src/main/java/structspeed/Workloads.java mirrors this file one to one.
/// Each method returns a value derived from its work so the JIT cannot discard it.
/// </summary>
public static class Workloads
{
    public const ulong Seed = 42;

    /// <summary>Work is split into this many chunks per core in the parallel cases (same in Java).</summary>
    public const int ChunksPerCore = 4;

    // ---- creation -------------------------------------------------------------------------------

    public static long CreateZeroed(Data[] data)
    {
        for (var i = 0; i < data.Length; i++)
            data[i] = new Data(0, 0);
        return data.Length;
    }

    public static long CreateRandom(Data[] data) => CreateRandom(data, 0, data.Length, Seed);

    public static long CreateFromGuid(Data[] data)
    {
        for (var i = 0; i < data.Length; i++)
            data[i] = new Data(Guid.NewGuid());
        return data[^1].GetHashCode();
    }

    /// <summary>The struct array is one contiguous block, so the OS crypto RNG fills it in one call.</summary>
    public static long CreateFromCryptoRng(Data[] data)
    {
        RandomNumberGenerator.Fill(MemoryMarshal.AsBytes(data.AsSpan()));
        return data[^1].GetHashCode();
    }

    // ---- comparison -----------------------------------------------------------------------------

    public static long CountMatches(Data[] data) => CountMatches(data, 0, data.Length, data[0]);

    public static long CountMatchesSimd(Data[] data) => Data.CountMatches(data, data[0]);

    // ---- collections ----------------------------------------------------------------------------

    /// <summary>Copies the unsorted source into <paramref name="work"/>, then sorts it.</summary>
    public static long CopyAndSort(Data[] source, Data[] work)
    {
        Array.Copy(source, work, source.Length);
        Array.Sort(work);
        return work[0].GetHashCode() ^ work[^1].GetHashCode();
    }

    public static long BuildHashSet(Data[] data)
    {
        var set = new HashSet<Data>(data.Length);
        foreach (var item in data)
            set.Add(item);
        return set.Count;
    }

    // ---- parallel -------------------------------------------------------------------------------

    /// <summary>SIMD comparison spread over all cores: at large sizes one core can't read memory fast enough.</summary>
    public static long ParallelCountMatchesSimd(Data[] data)
    {
        var chunks = ChunkCount(data.Length);
        var first = data[0];
        long total = 0;
        Parallel.For(0, chunks, () => 0L,
            (chunk, _, local) =>
            {
                var (start, end) = ChunkRange(data.Length, chunks, chunk);
                return local + Data.CountMatches(data.AsSpan(start, end - start), first);
            },
            local => Interlocked.Add(ref total, local));
        return total;
    }

    public static long ParallelCreateRandom(Data[] data)
    {
        var chunks = ChunkCount(data.Length);
        Parallel.For(0, chunks, chunk =>
        {
            var (start, end) = ChunkRange(data.Length, chunks, chunk);
            CreateRandom(data, start, end, Seed ^ ((ulong)chunk * 0x9E3779B97F4A7C15UL));
        });
        return data[^1].GetHashCode();
    }

    public static long ParallelCountMatches(Data[] data)
    {
        var chunks = ChunkCount(data.Length);
        var first = data[0];
        long total = 0;
        Parallel.For(0, chunks, () => 0L,
            (chunk, _, local) =>
            {
                var (start, end) = ChunkRange(data.Length, chunks, chunk);
                return local + CountMatches(data, start, end, first);
            },
            local => Interlocked.Add(ref total, local));
        return total;
    }

    // ---- helpers --------------------------------------------------------------------------------

    public static long CreateRandom(Data[] data, int start, int end, ulong seed)
    {
        var state = seed;
        for (var i = start; i < end; i++)
            data[i] = new Data(SplitMix64(ref state), SplitMix64(ref state));
        return (long)state;
    }

    public static long CountMatches(Data[] data, int start, int end, Data value)
    {
        long matches = 0;
        foreach (ref readonly var item in data.AsSpan(start, end - start))
            matches += value.Equals(item) ? 1 : 0;
        return matches;
    }

    public static ulong SplitMix64(ref ulong state)
    {
        var z = state += 0x9E3779B97F4A7C15UL;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9UL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBUL;
        return z ^ (z >> 31);
    }

    public static int ChunkCount(int length) => Math.Max(1, Math.Min(length, Environment.ProcessorCount * ChunksPerCore));

    public static (int Start, int End) ChunkRange(int length, int chunks, int chunk) =>
        ((int)((long)length * chunk / chunks), (int)((long)length * (chunk + 1) / chunks));
}
