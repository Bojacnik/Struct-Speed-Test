package structspeed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Every benchmark case as a plain static method, shared by the quick runner and JMH.
 * Mirrors csharp/StructSpeedTest.Core/Workloads.cs one to one.
 * Each method returns a value derived from its work so the JIT cannot discard it.
 */
public final class Workloads {
    public static final long SEED = 42;

    /** Work is split into this many chunks per core in the parallel cases (same in C#). */
    public static final int CHUNKS_PER_CORE = 4;

    private static final SecureRandom CRYPTO_RNG = new SecureRandom();

    private Workloads() {
    }

    // ---- creation -------------------------------------------------------------------------------

    public static long createZeroed(Data[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(0, 0);
        }
        return data.length;
    }

    public static long createRandom(Data[] data) {
        return createRandom(data, 0, data.length, SEED);
    }

    public static long createFromUuid(Data[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] = Data.fromUuid(UUID.randomUUID());
        }
        return data[data.length - 1].hashCode();
    }

    /** Objects can't be filled in place: fill one byte[] from the crypto RNG, then allocate an object per element. */
    public static long createFromCryptoRng(Data[] data, byte[] randomBytes) {
        CRYPTO_RNG.nextBytes(randomBytes);
        ByteBuffer buffer = ByteBuffer.wrap(randomBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(buffer.getLong(i * 16), buffer.getLong(i * 16 + 8));
        }
        return data[data.length - 1].hashCode();
    }

    // ---- comparison -----------------------------------------------------------------------------

    public static long countMatches(Data[] data) {
        return countMatches(data, 0, data.length, data[0]);
    }

    // ---- collections ----------------------------------------------------------------------------

    /** Copies the unsorted source into {@code work}, then sorts it. */
    public static long copyAndSort(Data[] source, Data[] work) {
        System.arraycopy(source, 0, work, 0, source.length);
        Arrays.sort(work);
        return work[0].hashCode() ^ work[work.length - 1].hashCode();
    }

    public static long buildHashSet(Data[] data) {
        HashSet<Data> set = HashSet.newHashSet(data.length);
        for (Data item : data) {
            set.add(item);
        }
        return set.size();
    }

    // ---- parallel -------------------------------------------------------------------------------

    public static long parallelCreateRandom(Data[] data) {
        int chunks = chunkCount(data.length);
        IntStream.range(0, chunks).parallel().forEach(chunk ->
                createRandom(data, chunkStart(data.length, chunks, chunk), chunkStart(data.length, chunks, chunk + 1),
                        SEED ^ (chunk * 0x9E3779B97F4A7C15L)));
        return data[data.length - 1].hashCode();
    }

    public static long parallelCountMatches(Data[] data) {
        int chunks = chunkCount(data.length);
        Data first = data[0];
        return IntStream.range(0, chunks).parallel()
                .mapToLong(chunk -> countMatches(data, chunkStart(data.length, chunks, chunk),
                        chunkStart(data.length, chunks, chunk + 1), first))
                .sum();
    }

    // ---- helpers --------------------------------------------------------------------------------

    public static long createRandom(Data[] data, int start, int end, long seed) {
        long[] state = {seed};
        for (int i = start; i < end; i++) {
            data[i] = new Data(splitMix64(state), splitMix64(state));
        }
        return state[0];
    }

    public static long countMatches(Data[] data, int start, int end, Data value) {
        long matches = 0;
        for (int i = start; i < end; i++) {
            matches += value.equals(data[i]) ? 1 : 0;
        }
        return matches;
    }

    public static long splitMix64(long[] state) {
        long z = (state[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static int chunkCount(int length) {
        return Math.max(1, Math.min(length, Runtime.getRuntime().availableProcessors() * CHUNKS_PER_CORE));
    }

    public static int chunkStart(int length, int chunks, int chunk) {
        return (int) ((long) length * chunk / chunks);
    }
}
