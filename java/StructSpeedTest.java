import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Java counterpart of the C# benchmark in ../Program.cs.
 * Same cases, same element count, same warmup/measurement harness.
 */
public final class StructSpeedTest {

    /** 16 bytes of data as two longs. Unlike the C# struct, every instance is a heap object. */
    record Data(long lo, long hi) {
        static Data fromUuid(UUID uuid) {
            return new Data(uuid.getLeastSignificantBits(), uuid.getMostSignificantBits());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Data other && ((lo ^ other.lo) | (hi ^ other.hi)) == 0;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(lo * 31 + hi);
        }
    }

    private static final int DEFAULT_COUNT = 50_000;
    private static final int MIN_WARMUP_ROUNDS = 30;
    private static final long MIN_WARMUP_MILLIS = 1_000; // give C2 time to compile the hot loops
    private static final int MEASURED_ROUNDS = 50;

    private static long sink;

    public static void main(String[] args) {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_COUNT;
        Data[] data = new Data[count];

        System.out.printf("Java %s | %s | %d cores | %,d elements | warmup >= %d rounds & %d ms | %d measured rounds%n",
                Runtime.version(), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                count, MIN_WARMUP_ROUNDS, MIN_WARMUP_MILLIS, MEASURED_ROUNDS);
        System.out.printf("%-34s %10s %10s %8s%n", "Benchmark", "median ms", "best ms", "ns/op");

        run("Create zeroed", count, () -> createZeroed(data));
        run("Create from PRNG (SplitMix64)", count, () -> createRandom(data, 42));
        run("Create from UUID.randomUUID()", count, () -> createFromUuid(data));
        byte[] randomBytes = new byte[count * 16];
        run("Create from crypto RNG (bulk)", count, () -> createFromCryptoRng(data, randomBytes));

        createRandom(data, 42); // deterministic contents for the comparison case
        run("Equals: first vs all", count, () -> countMatches(data));

        System.out.println("checksum: " + sink);
    }

    private static long createZeroed(Data[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(0, 0);
        }
        return data.length;
    }

    private static long createRandom(Data[] data, long seed) {
        long[] state = {seed};
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(splitMix64(state), splitMix64(state));
        }
        return state[0];
    }

    private static long createFromUuid(Data[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] = Data.fromUuid(UUID.randomUUID());
        }
        return data[data.length - 1].hashCode();
    }

    private static final SecureRandom CRYPTO_RNG = new SecureRandom();

    // Objects can't be filled in place, so fill one byte[] and then allocate an object per element.
    private static long createFromCryptoRng(Data[] data, byte[] randomBytes) {
        CRYPTO_RNG.nextBytes(randomBytes);
        ByteBuffer buffer = ByteBuffer.wrap(randomBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(buffer.getLong(i * 16), buffer.getLong(i * 16 + 8));
        }
        return data[data.length - 1].hashCode();
    }

    private static long countMatches(Data[] data) {
        Data first = data[0];
        int matches = 0;
        for (Data item : data) {
            matches += first.equals(item) ? 1 : 0;
        }
        return matches;
    }

    private static long splitMix64(long[] state) {
        long z = (state[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void run(String name, int count, LongSupplier body) {
        long warmupStart = System.nanoTime();
        for (int round = 0; round < MIN_WARMUP_ROUNDS
                || (System.nanoTime() - warmupStart) / 1_000_000 < MIN_WARMUP_MILLIS; round++) {
            sink += body.getAsLong();
        }

        double[] timesNs = new double[MEASURED_ROUNDS];
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long start = System.nanoTime();
            sink += body.getAsLong();
            timesNs[round] = System.nanoTime() - start;
        }

        Arrays.sort(timesNs);
        double median = timesNs[MEASURED_ROUNDS / 2];
        System.out.printf("%-34s %10.4f %10.4f %8.2f%n", name, median / 1e6, timesNs[0] / 1e6, median / count);
    }
}
