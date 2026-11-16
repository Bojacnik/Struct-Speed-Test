package structspeed;

import java.util.Arrays;
import java.util.UUID;

/**
 * Correctness checks for every workload (run with --verify, and at the start of race.sh).
 * Mirrors csharp/StructSpeedTest/Verification.cs: an awkward size, planted duplicates, and every optimized path
 * compared with the plain reference version.
 */
final class Verification {
    private static int failures;

    private Verification() {
    }

    static int run() {
        final int size = 100_003;
        Data[] data = new Data[size];
        Workloads.createRandom(data);

        // Plant duplicates: element 0 appears 7 times, and the last 1,000 elements copy earlier ones.
        for (int i : new int[]{1, 2, 3, 17, 4_099, size - 1}) data[i] = data[0];
        for (int i = size - 1_001; i < size - 1; i++) data[i] = data[i - 50_000];

        // Comparison: every path must agree.
        long expectedMatches = 0;
        for (Data item : data) {
            if (item.lo() == data[0].lo() && item.hi() == data[0].hi()) expectedMatches++;
        }
        check("equals.scalar", Workloads.countMatches(data), expectedMatches);
        check("parallel.equals", Workloads.parallelCountMatches(data), expectedMatches);
        check("equals with a distinct but equal object", new Data(1, 2).equals(new Data(1, 2)) ? 1 : 0, 1);

        // Ordering: unsigned, high half first.
        check("compareTo unsigned hi", Integer.signum(new Data(0, -1L).compareTo(new Data(-1L, 1))), 1);
        check("compareTo lo breaks tie", Integer.signum(new Data(-1L, 5).compareTo(new Data(1, 5))), 1);
        check("compareTo equal", new Data(3, 4).compareTo(new Data(3, 4)), 0);

        // Sort: the standard sort must be ordered, and radix sort must produce exactly the same array.
        Data[] sorted = new Data[size];
        Workloads.copyAndSort(data, sorted);
        boolean ordered = true;
        for (int i = 1; i < size; i++) {
            if (sorted[i - 1].compareTo(sorted[i]) > 0) {
                ordered = false;
                break;
            }
        }
        check("sort is ordered", ordered ? 1 : 0, 1);

        Data[] radix = new Data[size];
        HandOptimized.copyAndRadixSort(data, radix, new Data[size], new int[HandOptimized.RADIX_COUNTS_LENGTH]);
        check("radix sort == standard sort", Arrays.equals(sorted, radix) ? 1 : 0, 1);

        Data[] sameDigits = new Data[1_000];
        Arrays.fill(sameDigits, new Data(7, 9)); // every radix pass is skipped
        HandOptimized.copyAndRadixSort(sameDigits, new Data[1_000], new Data[1_000], new int[HandOptimized.RADIX_COUNTS_LENGTH]);
        Data[] oneDifferent = {new Data(5, 1), new Data(3, 1), new Data(4, 1)}; // only pass 0 runs (odd count)
        Data[] oneDifferentSorted = new Data[3];
        HandOptimized.copyAndRadixSort(oneDifferent, oneDifferentSorted, new Data[3], new int[HandOptimized.RADIX_COUNTS_LENGTH]);
        check("radix sort, odd number of passes",
                Arrays.equals(oneDifferentSorted, new Data[]{new Data(3, 1), new Data(4, 1), new Data(5, 1)}) ? 1 : 0, 1);

        // Hash sets: both must count the distinct values.
        long distinct = 1;
        for (int i = 1; i < size; i++) {
            if (!sorted[i].equals(sorted[i - 1])) distinct++;
        }
        check("hashset distinct count", Workloads.buildHashSet(data), distinct);
        check("open hash set distinct count", HandOptimized.buildOpenHashSet(data), distinct);
        check("open hash set with zero value",
                HandOptimized.buildOpenHashSet(new Data[]{new Data(0, 0), new Data(0, 0), new Data(0, 1)}), 2);

        // Creation paths fill every element.
        Data[] created = new Data[size];
        Workloads.parallelCreateRandom(created);
        check("parallel.create.prng fills the array", Arrays.stream(created).filter(d -> d == null).count(), 0);
        Workloads.createFromCryptoRng(created, new byte[size * 16]);
        check("create.crypto-bulk produces distinct values", HandOptimized.buildOpenHashSet(created), size);

        // A UUID keeps its bits.
        UUID uuid = UUID.randomUUID();
        Data fromUuid = Data.fromUuid(uuid);
        check("Data.fromUuid keeps the bits",
                fromUuid.lo() == uuid.getLeastSignificantBits() && fromUuid.hi() == uuid.getMostSignificantBits() ? 1 : 0, 1);

        System.out.println(failures == 0 ? "verify: all checks passed" : "verify: " + failures + " check(s) FAILED");
        return failures == 0 ? 0 : 1;
    }

    private static void check(String name, long actual, long expected) {
        if (actual == expected) return;
        failures++;
        System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
    }
}
