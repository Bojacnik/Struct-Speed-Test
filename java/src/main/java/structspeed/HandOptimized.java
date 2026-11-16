package structspeed;

/**
 * Hand-optimized track: custom algorithms instead of the standard library, kept separate from the main
 * "standard library, written normally" cases. Mirrors csharp/StructSpeedTest.Core/HandOptimized.cs.
 * Both still take the language's normal array of Data as input.
 */
public final class HandOptimized {
    public static final int RADIX_BITS = 16;
    public static final int RADIX_BUCKETS = 1 << RADIX_BITS;
    public static final int RADIX_PASSES = 128 / RADIX_BITS; // 8 passes over the 128-bit key

    /** Size of the counts buffer {@link #copyAndRadixSort} needs. */
    public static final int RADIX_COUNTS_LENGTH = RADIX_PASSES * RADIX_BUCKETS;

    private HandOptimized() {
    }

    /**
     * Copies the unsorted source into {@code work}, then LSD radix sorts it (16-bit digits, 8 passes) into the same
     * order as {@link Data#compareTo}. {@code temp} must be as long as the source; {@code counts} must hold
     * {@link #RADIX_COUNTS_LENGTH} ints. Moves references only; no allocation.
     */
    public static long copyAndRadixSort(Data[] source, Data[] work, Data[] temp, int[] counts) {
        int n = source.length;
        System.arraycopy(source, 0, work, 0, n);
        radixSort(work, temp, n, counts);
        return work[0].hashCode() ^ work[n - 1].hashCode();
    }

    public static void radixSort(Data[] data, Data[] temp, int n, int[] counts) {
        if (n < 2) return;
        java.util.Arrays.fill(counts, 0, RADIX_COUNTS_LENGTH, 0);

        // One read pass builds the histograms for all 8 digits.
        for (int i = 0; i < n; i++) {
            Data item = data[i];
            for (int pass = 0; pass < RADIX_PASSES; pass++) {
                counts[pass * RADIX_BUCKETS + digit(item, pass)]++;
            }
        }

        Data[] src = data;
        Data[] dst = temp;
        for (int pass = 0; pass < RADIX_PASSES; pass++) {
            int bucketBase = pass * RADIX_BUCKETS;
            // Every element has the same digit: this pass would not move anything.
            if (counts[bucketBase + digit(src[0], pass)] == n) continue;

            // Turn counts into start offsets.
            int offset = 0;
            for (int b = 0; b < RADIX_BUCKETS; b++) {
                int c = counts[bucketBase + b];
                counts[bucketBase + b] = offset;
                offset += c;
            }

            for (int i = 0; i < n; i++) {
                Data item = src[i];
                dst[counts[bucketBase + digit(item, pass)]++] = item;
            }

            Data[] swap = src;
            src = dst;
            dst = swap;
        }

        // An odd number of executed passes leaves the result in temp.
        if (src != data) {
            System.arraycopy(src, 0, data, 0, n);
        }
    }

    /** 16-bit digit {@code pass} of the key, least significant first (lo, then hi). */
    private static int digit(Data item, int pass) {
        return pass < 4
                ? (int) ((item.lo() >>> (pass * 16)) & 0xFFFF)
                : (int) ((item.hi() >>> ((pass - 4) * 16)) & 0xFFFF);
    }

    /**
     * Builds an open-addressing (linear probing) hash set of all elements and returns how many distinct ones it holds.
     * The two longs of each element are stored inline in a flat power-of-two table at most half full, so probing
     * never follows a reference: the closest Java gets to the C# version without value types.
     */
    public static long buildOpenHashSet(Data[] data) {
        int bits = tableBits(data.length);
        int mask = (1 << bits) - 1;
        long[] keys = new long[2 << bits]; // lo, hi, lo, hi, ...
        boolean[] used = new boolean[1 << bits];
        long count = 0;

        for (Data item : data) {
            long lo = item.lo();
            long hi = item.hi();
            int index = slot(lo, hi, bits);
            while (true) {
                if (!used[index]) {
                    used[index] = true;
                    keys[2 * index] = lo;
                    keys[2 * index + 1] = hi;
                    count++;
                    break;
                }
                if (((keys[2 * index] ^ lo) | (keys[2 * index + 1] ^ hi)) == 0) break;
                index = (index + 1) & mask;
            }
        }
        return count;
    }

    /** log2 of the smallest power of two that is at least twice {@code length} (load factor at most 0.5). */
    public static int tableBits(int length) {
        int bits = 1;
        while ((1L << bits) < 2L * length) bits++;
        return bits;
    }

    /** Same hash as Data.hashCode, spread with Fibonacci hashing so linear probing doesn't cluster. */
    private static int slot(long lo, long hi, int bits) {
        return (int) (((lo * 31 + hi) * 0x9E3779B97F4A7C15L) >>> (64 - bits));
    }
}
