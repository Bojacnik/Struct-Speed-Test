namespace StructSpeedTest;

/// <summary>
/// Hand-optimized track: custom algorithms instead of the standard library, kept separate from the main
/// "standard library, written normally" cases. java/src/main/java/structspeed/HandOptimized.java implements
/// the same algorithms. Both still take the language's normal array of Data as input.
/// </summary>
public static class HandOptimized
{
    public const int RadixBits = 16;
    public const int RadixBuckets = 1 << RadixBits;
    public const int RadixPasses = 128 / RadixBits; // 8 passes over the 128-bit key

    /// <summary>Size of the counts buffer <see cref="CopyAndRadixSort"/> needs.</summary>
    public const int RadixCountsLength = RadixPasses * RadixBuckets;

    /// <summary>
    /// Copies the unsorted source into <paramref name="work"/>, then LSD radix sorts it (16-bit digits, 8 passes)
    /// into the same order as <see cref="Data.CompareTo"/>. <paramref name="temp"/> must be as long as the source;
    /// <paramref name="counts"/> must hold <see cref="RadixCountsLength"/> ints. No allocation.
    /// </summary>
    public static long CopyAndRadixSort(Data[] source, Data[] work, Data[] temp, int[] counts)
    {
        Array.Copy(source, work, source.Length);
        RadixSort(work.AsSpan(0, source.Length), temp.AsSpan(0, source.Length), counts);
        return work[0].GetHashCode() ^ work[source.Length - 1].GetHashCode();
    }

    public static void RadixSort(Span<Data> data, Span<Data> temp, int[] counts)
    {
        var n = data.Length;
        if (n < 2) return;
        Array.Clear(counts, 0, RadixCountsLength);

        // One read pass builds the histograms for all 8 digits.
        foreach (ref readonly var item in data)
        {
            for (var pass = 0; pass < RadixPasses; pass++)
                counts[pass * RadixBuckets + Digit(item, pass)]++;
        }

        var src = data;
        var dst = temp;
        for (var pass = 0; pass < RadixPasses; pass++)
        {
            var bucketBase = pass * RadixBuckets;
            // Every element has the same digit: this pass would not move anything.
            if (counts[bucketBase + Digit(src[0], pass)] == n) continue;

            // Turn counts into start offsets.
            var offset = 0;
            for (var b = 0; b < RadixBuckets; b++)
            {
                var c = counts[bucketBase + b];
                counts[bucketBase + b] = offset;
                offset += c;
            }

            foreach (ref readonly var item in src)
                dst[counts[bucketBase + Digit(item, pass)]++] = item;

            var swap = src;
            src = dst;
            dst = swap;
        }

        // An odd number of executed passes leaves the result in temp.
        if (src != data)
            src.CopyTo(data);
    }

    /// <summary>16-bit digit <paramref name="pass"/> of the key, least significant first (lo, then hi).</summary>
    private static int Digit(in Data item, int pass) =>
        pass < 4 ? (int)((item.Lo >> (pass * 16)) & 0xFFFF) : (int)((item.Hi >> ((pass - 4) * 16)) & 0xFFFF);

    /// <summary>
    /// Builds an open-addressing (linear probing) hash set of all elements and returns how many distinct ones it holds.
    /// Elements are stored inline in a power-of-two table at most half full; no per-element allocation.
    /// </summary>
    public static long BuildOpenHashSet(Data[] data)
    {
        var bits = TableBits(data.Length);
        var mask = (1 << bits) - 1;
        var keys = new Data[1 << bits];
        var used = new bool[1 << bits];
        long count = 0;

        foreach (var item in data)
        {
            var index = Slot(item, bits);
            while (true)
            {
                if (!used[index])
                {
                    used[index] = true;
                    keys[index] = item;
                    count++;
                    break;
                }
                if (keys[index].Equals(item)) break;
                index = (index + 1) & mask;
            }
        }
        return count;
    }

    /// <summary>log2 of the smallest power of two that is at least twice <paramref name="length"/> (load factor at most 0.5).</summary>
    public static int TableBits(int length)
    {
        var bits = 1;
        while ((1L << bits) < 2L * length) bits++;
        return bits;
    }

    /// <summary>Same hash as Data.GetHashCode, spread with Fibonacci hashing so linear probing doesn't cluster.</summary>
    private static int Slot(in Data item, int bits) =>
        (int)(((item.Lo * 31 + item.Hi) * 0x9E3779B97F4A7C15UL) >> (64 - bits));
}
