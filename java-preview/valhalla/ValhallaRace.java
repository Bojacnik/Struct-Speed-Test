import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import structspeed.Harness;
import structspeed.Workloads;

/**
 * PREVIEW: Java value objects from Project Valhalla (JEP 401), early-access JDK builds only.
 *
 * A value record has no identity, so the JVM is free to store it inline (flattened) like a C# struct.
 * Whether it actually does depends on the build: early-access builds only guarantee flattening for small
 * values, and a nullable 16-byte value also needs a null marker. The memory footprint line shows what happened.
 *
 * Run (needs the main Java project built first): VALHALLA_HOME=/path/to/valhalla-jdk ./preview.sh valhalla
 */
public final class ValhallaRace {

    /** Same fields and methods as structspeed.Data, but declared as a value record. */
    value record VData(long lo, long hi) implements Comparable<VData> {
        @Override
        public boolean equals(Object o) {
            return o instanceof VData other && ((lo ^ other.lo) | (hi ^ other.hi)) == 0;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(lo * 31 + hi);
        }

        @Override
        public int compareTo(VData other) {
            return hi != other.hi ? Long.compareUnsigned(hi, other.hi) : Long.compareUnsigned(lo, other.lo);
        }
    }

    public static void main(String[] args) throws Exception {
        int[] sizes = {50_000, 1_000_000, 10_000_000};
        String csv = args.length > 0 ? args[0] : null;
        System.out.printf("Java Valhalla (early access) | %s | %s%n", Runtime.version(), System.getProperty("java.vm.name"));

        Harness harness = new Harness("java-valhalla-preview", "Java " + Runtime.version() + " (Valhalla EA)", false);
        for (int size : sizes) {
            VData[] data = new VData[size];
            VData[] sortWork = new VData[size];
            harness.printSizeHeader(size);

            harness.run("create.zeroed", size, () -> {
                for (int i = 0; i < data.length; i++) data[i] = new VData(0, 0);
                return data.length;
            });
            harness.run("create.prng", size, () -> createRandom(data));

            createRandom(data);
            harness.run("equals.scalar", size, () -> {
                VData first = data[0];
                long matches = 0;
                for (VData item : data) matches += first.equals(item) ? 1 : 0;
                return matches;
            });
            harness.run("sort", size, () -> {
                System.arraycopy(data, 0, sortWork, 0, data.length);
                Arrays.sort(sortWork);
                return sortWork[0].hashCode() ^ sortWork[sortWork.length - 1].hashCode();
            });
            harness.run("hashset", size, () -> {
                HashSet<VData> set = HashSet.newHashSet(data.length);
                for (VData item : data) set.add(item);
                return set.size();
            });
        }

        harness.footprint(1_000_000, n -> {
            VData[] data = new VData[n];
            createRandom(data);
            return data;
        });
        if (csv != null) harness.writeCsv(Path.of(csv));
        System.out.println("checksum: " + harness.sink());
    }

    static long createRandom(VData[] data) {
        long[] state = {Workloads.SEED};
        for (int i = 0; i < data.length; i++) {
            data[i] = new VData(Workloads.splitMix64(state), Workloads.splitMix64(state));
        }
        return state[0];
    }
}
