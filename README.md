# Struct-Speed-Test

How fast are C# structs compared with Java objects? This repo races the same workloads in both languages:
creating 16-byte values, comparing them, sorting them, hashing them, in parallel, and how much memory they take.

The **main race uses stable features only**: .NET 11 / C# 15 against JDK 25 (the current Java LTS).
Java features that are still incubating or early access are raced separately and clearly marked as **preview**.

## Layout

```
csharp/
  StructSpeedTest.Core/        Data.cs (the struct) + Workloads.cs (every benchmark case)
  StructSpeedTest/             quick runner (Harness.cs, Program.cs) - also builds with NativeAOT
  StructSpeedTest.Benchmarks/  the same cases under BenchmarkDotNet
java/                          Maven project, JDK 25
  src/main/java/structspeed/   Data.java (record) + Workloads.java + Harness.java + Main.java
  .../structspeed/jmh/         the same cases under JMH
java-preview/                  PREVIEW ONLY - not part of the main race
  vector/                      Vector API (jdk.incubator.vector, incubating)
  valhalla/                    value objects (Project Valhalla, JEP 401, early access)
tools/compare.py               merges result CSVs into a Markdown report
.github/workflows/race.yml     runs everything on GitHub Actions
```

`Workloads.cs` and `Workloads.java` mirror each other one to one, and both harnesses use identical timing rules,
so every row compares the same work.

## Running

Requires the **.NET 11 SDK**, **JDK 25+**, **Maven** and **Python 3**.

| Script | What it runs | Time |
|---|---|---|
| `./race.sh` | Main race: C# (JIT) vs Java (HotSpot JIT) at 50k, 1M and 10M elements | ~10 min |
| `./race.sh --quick --sizes 50000` | Smoke test | ~1 min |
| `./native.sh` | Ahead-of-time compiled: .NET NativeAOT vs GraalVM `native-image` (needs clang + zlib headers; GraalVM 25 for the Java side) | ~10 min |
| `./bench.sh [filter]` | Publication-grade: BenchmarkDotNet + JMH (with `-prof gc`) | 30+ min |
| `./preview.sh vector` | **Preview:** Java Vector API | ~1 min |
| `VALHALLA_HOME=... ./preview.sh valhalla` | **Preview:** Java value objects ([Valhalla EA JDK](https://jdk.java.net/valhalla/)) | ~5 min |

The runners also take `--filter text` (run matching cases only) and `--csv path`.
Results land in `results/` (`summary.md` is the side-by-side report).

On GitHub, the **Race** workflow runs the main, native and preview races on every push (quick mode) and puts the tables
in the run summary. Start it manually with **full** for all sizes, or **standard-tools** for BenchmarkDotNet + JMH.

## How it measures

Both harnesses (`Harness.cs` / `Harness.java`) follow the same rules:

1. **Warm-up** until at least 1 s and 30 rounds have run (or 4 s and 3 rounds for slow cases), so the JIT has produced fully optimised code.
2. **Measure** up to 50 rounds (at least 5, stopping after 3 s) and report the **median** round.
3. Count **heap allocation**, **GC count** and **GC pause time** across the timed rounds.
4. Add every result into a printed checksum so the compiler can't remove the work.
5. **Memory footprint:** fill a 1M-element array, force full GCs, and divide the heap growth by the element count.

The JVM runs with `-XX:+UseParallelGC -Xms4g -Xmx4g` (see `tools/env.sh`). Parallel GC is a throughput collector,
the closest match to the non-concurrent workstation GC the C# side uses.

## Cases

| Case | What it does |
|---|---|
| `create.zeroed` | Fill the array with `new Data(0, 0)` |
| `create.prng` | Create each element from a fast random generator (SplitMix64, same algorithm in both) |
| `create.guid` | One built-in random GUID per element (`Guid.NewGuid()` / `UUID.randomUUID()`) |
| `create.crypto-bulk` | One call to the OS crypto RNG for all bytes, then turn them into elements |
| `equals.scalar` | Compare element 0 with every element through `Equals` |
| `equals.simd` | C#: `Data.CountMatches` compares 2 (AVX2) or 4 (AVX-512) elements per instruction. Java: no stable API, see preview |
| `sort` | Copy the unsorted array, then sort it (unsigned order by high half, then low half) |
| `hashset` | Build a `HashSet` of all elements (both sides use the same hash function) |
| `parallel.equals` | `equals.scalar` split over all cores (`Parallel.For` / parallel `IntStream`, same chunking) |
| `parallel.create.prng` | `create.prng` split over all cores |
| `memory.footprint` | Heap bytes per element of a filled array |

## Results

RESULTS_PLACEHOLDER

## Preview: Java features that aren't stable yet

These are **not** part of the main race. They show where Java is heading, not what you can ship today.

### Vector API (incubator in JDK 25)

`java-preview/vector/VectorRace.java` gives Java the same SIMD comparison as C#'s `equals.simd`.
The Vector API can't read fields from an array of objects, so the data first has to be copied into a flat
`long[]` (lo, hi, lo, hi, ...), which is exactly the layout a C# struct array already has.

PREVIEW_VECTOR_PLACEHOLDER

### Value objects (Project Valhalla, early access)

`java-preview/valhalla/ValhallaRace.java` declares the data as a `value record`. Value objects have no identity,
so the JVM may store them inline in arrays the way C# stores structs. Early-access builds only guarantee that
for small values; a nullable 16-byte value also needs a null marker, so check the memory footprint line to see
whether this build actually flattened the array.

This one needs the Valhalla early-access JDK, so it runs in the GitHub Actions **preview** job (or locally with `VALHALLA_HOME`).

## Notes

- **BenchmarkDotNet + .NET 11:** BenchmarkDotNet 0.15.8 (the latest release) doesn't recognise .NET 11 yet, so `StructSpeedTest.Benchmarks` runs in-process (`--inProcess`). Remove that once a release supports `net11.0`.
- **NativeAOT and SIMD:** NativeAOT targets a baseline x64 CPU by default, which turns AVX2/AVX-512 off. `native.sh` passes `IlcInstructionSet=native` (and GraalVM gets `-march=native`) so both sides use the build machine's CPU.
- **C# 15:** the project uses C# 15, but its new features (unions, closed hierarchies, labeled `break`/`continue`, extension indexers, collection expression arguments) are about expressiveness, not speed, so none of them are used here.
- Numbers depend on CPU, OS and runtime versions. Run it on your own machine before drawing conclusions.
