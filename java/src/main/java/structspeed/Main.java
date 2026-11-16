package structspeed;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Quick C# vs Java race. The C# counterpart is csharp/StructSpeedTest/Program.cs.
 * Usage: java -cp target/benchmarks.jar structspeed.Main [--sizes 50000,1000000,10000000] [--csv path] [--filter text] [--quick] [--label text]
 */
public final class Main {
    private static final int[] DEFAULT_SIZES = {50_000, 1_000_000, 10_000_000};
    private static final int FOOTPRINT_SIZE = 1_000_000;

    public static void main(String[] args) throws Exception {
        int[] sizes = DEFAULT_SIZES;
        String csvPath = null;
        String filter = null;
        boolean quick = false;
        String label = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sizes" -> sizes = Arrays.stream(args[++i].split(",")).mapToInt(s -> Integer.parseInt(s.replace("_", ""))).toArray();
                case "--csv" -> csvPath = args[++i];
                case "--filter" -> filter = args[++i].toLowerCase(Locale.ROOT);
                case "--quick" -> quick = true;
                case "--label" -> label = args[++i];
                default -> {
                    System.err.println("Unknown argument '" + args[i] + "'. Usage: [--sizes a,b,c] [--csv path] [--filter text] [--quick] [--label text]");
                    System.exit(1);
                }
            }
        }

        String runtime = "Java " + Runtime.version().feature() + " (" + System.getProperty("java.vm.name") + ")"
                + (label != null ? " " + label : "");
        System.out.printf("Java | %s | %s | %s | %d cores%n", Runtime.version(), System.getProperty("java.vm.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());

        Harness harness = new Harness("java", runtime, quick);
        for (int size : sizes) {
            Data[] data = new Data[size];
            Data[] sortWork = new Data[size];
            byte[] randomBytes = new byte[size * 16];
            harness.printSizeHeader(size);
            final String f = filter;
            CaseRunner run = (name, body) -> {
                if (f == null || name.contains(f)) harness.run(name, size, body);
            };

            run.accept("create.zeroed", () -> Workloads.createZeroed(data));
            run.accept("create.prng", () -> Workloads.createRandom(data));
            run.accept("create.guid", () -> Workloads.createFromUuid(data));
            run.accept("create.crypto-bulk", () -> Workloads.createFromCryptoRng(data, randomBytes));

            Workloads.createRandom(data); // deterministic contents for the remaining cases
            run.accept("equals.scalar", () -> Workloads.countMatches(data));
            // equals.simd / parallel.equals.simd: no stable Java SIMD API (the Vector API is incubating) - see java-preview/vector
            run.accept("sort", () -> Workloads.copyAndSort(data, sortWork));
            run.accept("hashset", () -> Workloads.buildHashSet(data));
            run.accept("parallel.equals", () -> Workloads.parallelCountMatches(data));
            run.accept("parallel.create.prng", () -> Workloads.parallelCreateRandom(data));
        }

        if (filter == null || "memory.footprint".contains(filter)) {
            harness.footprint(FOOTPRINT_SIZE, n -> {
                Data[] data = new Data[n];
                Workloads.createRandom(data);
                return data;
            });
        }

        if (csvPath != null) harness.writeCsv(Path.of(csvPath));
        System.out.println("checksum: " + harness.sink());
    }

    @FunctionalInterface
    private interface CaseRunner {
        void accept(String name, LongSupplier body);
    }
}
