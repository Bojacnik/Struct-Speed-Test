namespace StructSpeedTest;

/// <summary>
/// Correctness checks for every workload (run with --verify, and at the start of race.sh).
/// Uses an awkward size (odd, not a multiple of any vector width or chunk count) and planted duplicates,
/// and compares every optimized path with the plain reference version.
/// java/src/main/java/structspeed/Verification.java runs the same checks.
/// </summary>
internal static class Verification
{
    private static int s_failures;

    public static int Run()
    {
        const int size = 100_003;
        var data = new Data[size];
        Workloads.CreateRandom(data);

        // Plant duplicates: element 0 appears 7 times, and the last 1,000 elements copy earlier ones.
        foreach (var i in new[] { 1, 2, 3, 17, 4_099, size - 1 })
            data[i] = data[0];
        for (var i = size - 1_001; i < size - 1; i++)
            data[i] = data[i - 50_000];

        // Comparison: every path must agree.
        long expectedMatches = 0;
        foreach (var item in data)
            if (item.Lo == data[0].Lo && item.Hi == data[0].Hi) expectedMatches++;
        Check("equals.scalar", Workloads.CountMatches(data), expectedMatches);
        Check("equals.simd", Workloads.CountMatchesSimd(data), expectedMatches);
        Check("parallel.equals", Workloads.ParallelCountMatches(data), expectedMatches);
        Check("parallel.equals.simd", Workloads.ParallelCountMatchesSimd(data), expectedMatches);
        for (var length = 0; length <= 9; length++) // SIMD tail handling on tiny inputs
            Check($"equals.simd (length {length})", Data.CountMatches(data.AsSpan(0, length), data[0]),
                Workloads.CountMatches(data, 0, length, data[0]));

        // Ordering: unsigned, high half first.
        Check("CompareTo unsigned hi", new Data(0, ulong.MaxValue).CompareTo(new Data(ulong.MaxValue, 1)), 1);
        Check("CompareTo lo breaks tie", new Data(ulong.MaxValue, 5).CompareTo(new Data(1, 5)), 1);
        Check("CompareTo equal", new Data(3, 4).CompareTo(new Data(3, 4)), 0);

        // Sort: the standard sort must be ordered, and radix sort must produce exactly the same array.
        var sorted = new Data[size];
        Workloads.CopyAndSort(data, sorted);
        var ordered = true;
        for (var i = 1; i < size; i++)
            if (sorted[i - 1].CompareTo(sorted[i]) > 0) { ordered = false; break; }
        Check("sort is ordered", ordered ? 1 : 0, 1);

        var radix = new Data[size];
        var temp = new Data[size];
        HandOptimized.CopyAndRadixSort(data, radix, temp, new int[HandOptimized.RadixCountsLength]);
        Check("radix sort == standard sort", sorted.AsSpan().SequenceEqual(radix) ? 1 : 0, 1);

        var sameDigits = Enumerable.Repeat(new Data(7, 9), 1_000).ToArray(); // every radix pass is skipped
        HandOptimized.CopyAndRadixSort(sameDigits, new Data[1_000], new Data[1_000], new int[HandOptimized.RadixCountsLength]);
        var oneDifferent = new[] { new Data(5, 1), new Data(3, 1), new Data(4, 1) }; // only pass 0 runs (odd count)
        var oneDifferentSorted = new Data[3];
        HandOptimized.CopyAndRadixSort(oneDifferent, oneDifferentSorted, new Data[3], new int[HandOptimized.RadixCountsLength]);
        Check("radix sort, odd number of passes",
            oneDifferentSorted.SequenceEqual([new Data(3, 1), new Data(4, 1), new Data(5, 1)]) ? 1 : 0, 1);

        // Hash sets: both must count the distinct values.
        long distinct = 1;
        for (var i = 1; i < size; i++)
            if (!sorted[i].Equals(sorted[i - 1])) distinct++;
        Check("hashset distinct count", Workloads.BuildHashSet(data), distinct);
        Check("open hash set distinct count", HandOptimized.BuildOpenHashSet(data), distinct);
        Check("open hash set with zero value", HandOptimized.BuildOpenHashSet([new Data(0, 0), new Data(0, 0), new Data(0, 1)]), 2);

        // Creation paths fill every element.
        var created = new Data[size];
        Workloads.ParallelCreateRandom(created);
        Check("parallel.create.prng fills the array", created.Count(d => d.Equals(default)), 0);
        Workloads.CreateFromCryptoRng(created);
        Check("create.crypto-bulk produces distinct values", HandOptimized.BuildOpenHashSet(created), size);

        // A Guid keeps its exact bytes.
        var guid = Guid.NewGuid();
        Span<byte> bytes = stackalloc byte[Data.Size];
        new Data(guid).WriteTo(bytes);
        Check("Data(Guid) keeps the bytes", bytes.SequenceEqual(guid.ToByteArray()) ? 1 : 0, 1);
        Check("Data(bytes) round-trips", new Data(bytes).Equals(new Data(guid)) ? 1 : 0, 1);

        Console.WriteLine(s_failures == 0 ? "verify: all checks passed" : $"verify: {s_failures} check(s) FAILED");
        return s_failures == 0 ? 0 : 1;
    }

    private static void Check(string name, long actual, long expected)
    {
        if (actual == expected) return;
        s_failures++;
        Console.WriteLine($"FAIL {name}: expected {expected}, got {actual}");
    }
}
