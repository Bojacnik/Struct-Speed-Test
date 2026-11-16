import java.nio.file.Path;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;
import structspeed.Data;
import structspeed.Harness;
import structspeed.Workloads;

/**
 * PREVIEW: Java SIMD comparison with the Vector API (jdk.incubator.vector, still incubating in JDK 25).
 *
 * The Vector API can't load fields out of an array of objects, so the data has to be copied into a flat
 * long[] (lo, hi, lo, hi, ...) first - i.e. the same memory layout a C# struct array has natively.
 * Compare with "equals.simd" from the C# runner.
 *
 * Run (needs the main Java project built first): ./preview.sh vector
 */
public final class VectorRace {
    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

    public static void main(String[] args) throws Exception {
        int[] sizes = {50_000, 1_000_000, 10_000_000};
        String csv = args.length > 0 ? args[0] : null;
        System.out.printf("Java Vector API (incubator) | %s | %d-bit vectors%n", Runtime.version(), SPECIES.vectorBitSize());

        Harness harness = new Harness("java-vector-preview", "Java " + Runtime.version().feature() + " + Vector API (incubator)", false);
        for (int size : sizes) {
            Data[] objects = new Data[size];
            Workloads.createRandom(objects);
            long[] flat = new long[size * 2];
            for (int i = 0; i < size; i++) {
                flat[2 * i] = objects[i].lo();
                flat[2 * i + 1] = objects[i].hi();
            }
            objects = null; // only the flat copy is used below

            harness.printSizeHeader(size);
            harness.run("equals.scalar (flat)", size, () -> countMatchesScalar(flat, flat[0], flat[1]));
            harness.run("equals.simd", size, () -> countMatchesVector(flat, flat[0], flat[1]));
        }
        if (csv != null) harness.writeCsv(Path.of(csv));
        System.out.println("checksum: " + harness.sink());
    }

    static long countMatchesScalar(long[] flat, long lo, long hi) {
        long matches = 0;
        for (int i = 0; i < flat.length; i += 2) {
            matches += ((flat[i] ^ lo) | (flat[i + 1] ^ hi)) == 0 ? 1 : 0;
        }
        return matches;
    }

    /** Same algorithm as the C# Data.CountMatches: compare whole vectors, then count lane pairs that both matched. */
    static long countMatchesVector(long[] flat, long lo, long hi) {
        long[] pattern = new long[SPECIES.length()];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (i & 1) == 0 ? lo : hi;
        }
        LongVector target = LongVector.fromArray(SPECIES, pattern, 0);

        long matches = 0;
        int i = 0;
        int bound = SPECIES.loopBound(flat.length);
        for (; i < bound; i += SPECIES.length()) {
            long mask = LongVector.fromArray(SPECIES, flat, i).eq(target).toLong();
            matches += Long.bitCount(mask & (mask >>> 1) & 0x5555555555555555L);
        }
        for (; i < flat.length; i += 2) {
            matches += ((flat[i] ^ lo) | (flat[i + 1] ^ hi)) == 0 ? 1 : 0;
        }
        return matches;
    }
}
