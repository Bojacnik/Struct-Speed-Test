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

### Main race (JIT, stable features)

4-core x64 Linux cloud VM with AVX-512, .NET 11.0.0 vs OpenJDK 25.0.5. Median **ns per element, C# / Java**, faster in bold:

| Case | 50k | 1M | 10M |
|---|---:|---:|---:|
| `create.zeroed` | **0.34** / 2.26 | **0.58** / 4.40 | **1.74** / 15.89 |
| `create.prng` | **1.50** / 4.13 | **1.58** / 4.07 | **2.17** / 5.59 |
| `create.guid` | 263.63 / **163.83** | 268.37 / **169.13** | 265.19 / **198.69** |
| `create.crypto-bulk` | **2.31** / 114.18 | **3.05** / 115.29 | **3.74** / 131.04 |
| `equals.scalar` | 0.77 / **0.54** | **1.35** / 2.04 | 2.34 / **2.29** |
| `equals.simd` | 0.23 / – | 0.91 / – | 1.41 / – |
| `sort` | **60.81** / 127.59 | **79.70** / 266.92 | **99.04** / 450.63 |
| `hashset` | 19.70 / **16.02** | **29.51** / 40.63 | **52.83** / 70.21 |
| `parallel.equals` | **0.46** / 0.76 | **0.62** / 0.65 | **0.61** / 1.20 |
| `parallel.create.prng` | **0.91** / 2.57 | **0.87** / 1.46 | **0.67** / 8.36 |

| | C# | Java |
|---|---:|---:|
| Memory per element | **16 bytes** | 36 bytes |
| Heap allocated per created element | **0 bytes** | 32 bytes |

What stands out:

- **Creating** values: C# writes 16 bytes into one flat block; Java allocates a 32-byte object per element plus a 4-byte reference in the array. At 10M elements that's about 9× faster in C# (`create.zeroed`) and 12× faster in parallel.
- **Memory:** 16 vs 36 bytes per element. A struct array has no per-element object header and no references.
- **Sort:** 2–4.5× faster in C#, and the gap grows with size: Java has to follow a reference for every comparison, so cache misses pile up.
- **Bulk random fill:** a struct array is raw memory, so one `RandomNumberGenerator.Fill` call fills it. Java has to allocate an object per element afterwards.
- **Java still wins** the built-in GUID (`UUID.randomUUID()` buffers its random source; .NET asks the OS once per GUID on Linux) and, at 50k elements, `equals.scalar` and `hashset`, where everything fits in cache and HotSpot's code is excellent. At 10M, `equals.scalar` is a tie: both are limited by memory bandwidth.
- `equals.simd` has no Java column because Java has no stable SIMD API; see the preview section.

### Native race (ahead-of-time compiled)

From the GitHub Actions **native** job (ubuntu-24.04 runner, quick mode), .NET 11 NativeAOT vs GraalVM Community 25.0.2 `native-image`, 1M elements, ns per element:

| Case | C# NativeAOT | Java native-image |
|---|---:|---:|
| `create.zeroed` | **0.55** | 3.84 |
| `create.prng` | **1.91** | 3.93 |
| `create.guid` | 324.11 | **279.54** |
| `create.crypto-bulk` | **2.62** | 186.98 |
| `equals.scalar` | **0.69** | 1.62 |
| `equals.simd` | 0.54 | – |
| `sort` | **106.01** | 276.67 |
| `hashset` | **22.00** | 48.41 |
| `parallel.equals` | **0.32** | 0.85 |
| `parallel.create.prng` | **1.08** | 1.58 |
| Memory per element | **16.0 bytes** | 48.4 bytes |

GraalVM Community's native images use the Serial GC and can't report allocations (they show as `n/a`). Oracle GraalVM adds G1 and profile-guided optimisation, which would narrow the gap.

These come from a different machine than the main race, so compare within a table, not across tables.

## Preview: Java features that aren't stable yet

These are **not** part of the main race. They show where Java is heading, not what you can ship today.

### Vector API (incubator in JDK 25)

`java-preview/vector/VectorRace.java` gives Java the same SIMD comparison as C#'s `equals.simd`.
The Vector API can't read fields from an array of objects, so the data first has to be copied into a flat
`long[]` (lo, hi, lo, hi, ...), which is exactly the layout a C# struct array already has.

From the GitHub Actions **preview** job (JDK 25.0.5, 512-bit vectors), ns per element:

| Case | 50k | 1M | 10M |
|---|---:|---:|---:|
| Java `equals.scalar` on the flat `long[]` | 0.42 | 0.65 | 1.48 |
| Java `equals.simd` (Vector API) | **0.19** | **0.59** | **1.09** |

With the data flattened by hand, Java's SIMD is as fast as C#'s. The catch is that you have to give up objects to get there.

### Value objects (Project Valhalla, early access)

`java-preview/valhalla/ValhallaRace.java` declares the data as a `value record`. Value objects have no identity,
so the JVM may store them inline in arrays the way C# stores structs. Early-access builds only guarantee that
for small values; a nullable 16-byte value also needs a null marker, so check the memory footprint line to see
whether this build actually flattened the array.

This one needs the Valhalla early-access JDK, so it runs in the GitHub Actions **preview** job (or locally with `VALHALLA_HOME`).

Result with build `27-jep401ea3+1-1` (preview job, ns per element):

| Case | 50k | 1M | 10M |
|---|---:|---:|---:|
| `create.zeroed` | 2.77 | 2.87 | 10.80 |
| `create.prng` | 3.58 | 3.73 | 11.99 |
| `equals.scalar` | 0.87 | 1.49 | 3.73 |
| `sort` | 116.35 | 218.30 | 373.83 |
| `hashset` | 10.38 | 31.28 | 104.04 |

| | Valhalla `value record` |
|---|---:|
| Heap allocated per created element | 40 bytes |
| Memory per element | 44 bytes |

**This build did not flatten the array.** Every value was still allocated as its own heap object, and the footprint is
higher than a normal record's 36 bytes. That matches the JEP 401 rules: a nullable 16-byte value needs a null marker and
atomic updates, which is more than the JVM can flatten today. Valhalla doesn't make Java struct-like for this data yet;
re-run the preview job when a new early-access build comes out.

## Notes

- **BenchmarkDotNet + .NET 11:** BenchmarkDotNet 0.15.8 (the latest release) doesn't recognise .NET 11 yet, so `StructSpeedTest.Benchmarks` runs in-process (`--inProcess`). Remove that once a release supports `net11.0`.
- **NativeAOT and SIMD:** NativeAOT targets a baseline x64 CPU by default, which turns AVX2/AVX-512 off. `native.sh` passes `IlcInstructionSet=native` (and GraalVM gets `-march=native`) so both sides use the build machine's CPU.
- **C# 15:** the project uses C# 15, but its new features (unions, closed hierarchies, labeled `break`/`continue`, extension indexers, collection expression arguments) are about expressiveness, not speed, so none of them are used here.
- Numbers depend on CPU, OS and runtime versions. Run it on your own machine before drawing conclusions.
