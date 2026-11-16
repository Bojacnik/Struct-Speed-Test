package structspeed;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Minimal benchmark harness. csharp/StructSpeedTest/Harness.cs implements the same rules:
 * warm up until at least 1 s and 30 rounds have run (or 4 s and 3 rounds for slow cases), then time up to
 * 50 rounds (at least 5, stopping after 3 s) and report the median. Allocations and GCs are measured
 * across the timed rounds. Public so the preview runners in java-preview/ can reuse it.
 */
public final class Harness {
    private static final int MIN_WARMUP_ROUNDS = 30;
    private static final int MIN_SLOW_WARMUP_ROUNDS = 3;
    private static final int MIN_ROUNDS = 5;

    private final String lang;
    private final String runtime;
    private final double minWarmupSec;
    private final double maxWarmupSec;
    private final int maxRounds;
    private final double maxMeasureSec;
    private final StringBuilder csv = new StringBuilder(
            "lang,runtime,size,case,median_ms,best_ms,ns_per_op,alloc_bytes_per_op,gcs_per_round,gc_pause_ms_per_round,bytes_per_element\n");
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private long sink;

    public Harness(String lang, String runtime, boolean quick) {
        this.lang = lang;
        this.runtime = runtime;
        this.minWarmupSec = quick ? 0.5 : 1.0;
        this.maxWarmupSec = quick ? 2.0 : 4.0;
        this.maxRounds = quick ? 20 : 50;
        this.maxMeasureSec = quick ? 1.5 : 3.0;
    }

    public long sink() {
        return sink;
    }

    public void printSizeHeader(int size) {
        System.out.println();
        System.out.printf("-- %,d elements%n", size);
        System.out.printf("%-24s %11s %9s %11s %10s %12s%n", "Case", "median ms", "ns/op", "alloc B/op", "GCs/round", "GC ms/round");
    }

    public void run(String name, int size, LongSupplier body) {
        long warmupStart = System.nanoTime();
        for (int round = 1; ; round++) {
            sink += body.getAsLong();
            double elapsed = (System.nanoTime() - warmupStart) / 1e9;
            if (elapsed >= minWarmupSec && round >= MIN_WARMUP_ROUNDS) break;
            if (elapsed >= maxWarmupSec && round >= MIN_SLOW_WARMUP_ROUNDS) break;
        }

        List<Double> times = new ArrayList<>(maxRounds);
        long allocBefore = allocatedBytes();
        long gcsBefore = gcCount();
        long pauseBefore = gcTimeMs();
        long measureStart = System.nanoTime();
        while (times.size() < maxRounds
                && !(times.size() >= MIN_ROUNDS && (System.nanoTime() - measureStart) / 1e9 >= maxMeasureSec)) {
            long start = System.nanoTime();
            sink += body.getAsLong();
            times.add((double) (System.nanoTime() - start));
        }
        long allocAfter = allocatedBytes();
        long gcs = gcCount() - gcsBefore;
        long pauseMs = gcTimeMs() - pauseBefore;

        times.sort(null);
        int rounds = times.size();
        double median = times.get(rounds / 2);
        // Not every runtime can count allocations (e.g. some native images): report n/a instead of a wrong number.
        Double allocPerOp = allocBefore < 0 || allocAfter < 0 ? null : (allocAfter - allocBefore) / ((double) rounds * size);
        double gcsPerRound = gcs / (double) rounds;
        double pausePerRound = pauseMs / (double) rounds;

        System.out.printf(Locale.ROOT, "%-24s %11.3f %9.2f %11s %10.2f %12.3f%n", name, median / 1e6, median / size,
                allocPerOp == null ? "n/a" : String.format(Locale.ROOT, "%.1f", allocPerOp), gcsPerRound, pausePerRound);
        appendCsv(size, name, median / 1e6, times.get(0) / 1e6, median / size, allocPerOp, gcsPerRound, pausePerRound, null);
    }

    /** Heap bytes retained per element by a filled array of {@code size} elements built by {@code build}. */
    public void footprint(int size, IntFunction<Object> build) {
        long before = settledHeap();
        Object data = build.apply(size);
        long after = settledHeap();
        java.lang.ref.Reference.reachabilityFence(data);

        double perElement = (after - before) / (double) size;
        System.out.println();
        System.out.printf(Locale.ROOT, "Memory footprint: %.1f bytes per element (array of %,d filled elements)%n", perElement, size);
        appendCsv(size, "memory.footprint", null, null, null, null, null, null, perElement);
    }

    public void writeCsv(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, csv);
    }

    private long allocatedBytes() {
        try {
            return threads.getTotalThreadAllocatedBytes();
        } catch (UnsupportedOperationException e) {
            return -1;
        }
    }

    private long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : collectors) total += Math.max(0, gc.getCollectionCount());
        return total;
    }

    private long gcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : collectors) total += Math.max(0, gc.getCollectionTime());
        return total;
    }

    private static long settledHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private void appendCsv(int size, String name, Double... values) {
        // The CSV is not quoted, so keep commas out of the free-text runtime label.
        csv.append(lang).append(',').append(runtime.replace(',', ';')).append(',').append(size).append(',').append(name);
        for (Double value : values) {
            csv.append(',');
            if (value != null) csv.append(String.format(Locale.ROOT, "%.6g", value));
        }
        csv.append('\n');
    }
}
