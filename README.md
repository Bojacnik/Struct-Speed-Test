# Struct-Speed-Test

How fast are C# structs compared with Java objects? This repo races the same workloads in both languages:
creating 16-byte values, comparing them, sorting them, hashing them, in parallel, and how much memory they take.

The **main race uses stable features only**: .NET 11 / C# 15 against JDK 25 (the current Java LTS).
Java features that are still incubating or early access are raced separately and clearly marked as **preview**.

## Layout

```
csharp/
  StructSpeedTest.Core/        Data.cs (the struct) + Workloads.cs (every benchmark case)
                               + HandOptimized.cs (radix sort, open-addressing hash set)
  StructSpeedTest/             quick runner (Harness.cs, Program.cs, Verification.cs) - also builds with NativeAOT
  StructSpeedTest.Benchmarks/  the same cases under BenchmarkDotNet
java/                          Maven project, JDK 25
  src/main/java/structspeed/   Data.java (record) + Workloads.java + HandOptimized.java
                               + Harness.java + Main.java + Verification.java
  .../structspeed/jmh/         the same cases under JMH
java-preview/                  PREVIEW ONLY - not part of the main race
  vector/                      Vector API (jdk.incubator.vector, incubating)
  valhalla/                    value objects (Project Valhalla, JEP 401, early access)
tools/compare.py               merges result CSVs into a Markdown report
tools/env.sh                   shared settings for the scripts (JVM flags, tool checks)
.github/workflows/race.yml     runs everything on GitHub Actions
```

`Workloads.*`, `HandOptimized.*` and `Verification.*` mirror each other one to one between the languages, and both
harnesses use identical timing rules, so every row compares the same work.

## Running

Requires the **.NET 11 SDK**, **JDK 25+**, **Maven** and **Python 3**.

| Script | What it runs | Time |
|---|---|---|
| `./race.sh` | Main race: C# (JIT) vs Java (HotSpot JIT, default and tuned GC) at 50k, 1M and 10M elements | ~15 min |
| `./race.sh --quick --sizes 50000` | Smoke test | ~1 min |
| `./native.sh` | Ahead-of-time compiled: .NET NativeAOT vs GraalVM `native-image` (needs clang + zlib headers; GraalVM 25 for the Java side) | ~10 min |
| `./bench.sh [filter]` | Publication-grade: BenchmarkDotNet + JMH (with `-prof gc`) | 30+ min |
| `./preview.sh vector` | **Preview:** Java Vector API | ~1 min |
| `VALHALLA_HOME=... ./preview.sh valhalla` | **Preview:** Java value objects ([Valhalla EA JDK](https://jdk.java.net/valhalla/)) | ~5 min |

The runners also take `--filter text` (run matching cases only), `--csv path`, and `--verify` (correctness checks only,
no timing; takes a second).
Results land in `results/` (`summary.md` is the side-by-side report).

On GitHub, the **Race** workflow runs the main, native and preview races on every push (quick mode) and puts the tables
in the run summary. It also runs the correctness checks with AVX-512 and with all of AVX switched off, so every SIMD
code path is tested whatever CPU the runner has. Start it manually with **full** for all sizes, or **standard-tools** for BenchmarkDotNet + JMH.

## How it measures

Before any timing, `race.sh` and `native.sh` run the **correctness checks** (`Verification.cs` / `Verification.java`):
every optimized path (SIMD, parallel, radix sort, open-addressing hash set) is compared with the plain reference
version on an awkward size with planted duplicates. A wrong result stops the run.

Both harnesses (`Harness.cs` / `Harness.java`) follow the same rules:

1. **Warm-up** until at least 1 s and 30 rounds have run (or 4 s and 3 rounds for slow cases), so the JIT has produced fully optimised code.
2. **Measure** up to 50 rounds (at least 5, stopping after 3 s) and report the **median** round.
3. Count **heap allocation**, **GC count** and **GC pause time** across the timed rounds.
4. Add every result into a printed checksum so the compiler can't remove the work.
5. **Memory footprint:** fill a 1M-element array, force full GCs, and divide the heap growth by the element count.

Java runs twice in the main race (flags in `tools/env.sh`):

- **Java (default):** `-XX:+UseParallelGC -Xms4g -Xmx4g -XX:+UseCompactObjectHeaders`. Parallel GC is a throughput
  collector, the closest match to the non-concurrent workstation GC the C# side uses. Compact object headers
  ([JEP 519](https://openjdk.org/jeps/519)) are a stable feature since JDK 25, just not on by default: they shrink each
  `Data` object from 32 to 24 bytes.
- **Java (tuned GC):** the same, plus a 4 GB young generation (`-Xmn4g`). Most of Java's creation cost at large sizes is
  the GC copying millions of objects that are still referenced from the array. A big young generation means the timed
  rounds rarely trigger that copy. It mostly **postpones** the work rather than removing it (a long-running program pays it
  eventually), which is why it's a separate column, not the default.

## Cases

| Case | What it does |
|---|---|
| `create.zeroed` | Fill the array with `new Data(0, 0)` |
| `create.prng` | Create each element from a fast random generator (SplitMix64, same algorithm in both) |
| `create.guid` | One built-in random GUID per element (`Guid.NewGuid()` / `UUID.randomUUID()`) |
| `create.crypto-bulk` | One crypto-RNG call for all bytes, then turn them into elements. C#: `RandomNumberGenerator.Fill` (OS RNG). Java: `SecureRandom.getInstance("DRBG")`, 3.4× faster than the Linux default `NativePRNG`, which mixes every read with SHA1PRNG output |
| `equals.scalar` | Compare element 0 with every element through `Equals` |
| `equals.simd` | C#: `Data.CountMatches` compares 2 (AVX2) or 4 (AVX-512) elements per instruction. Java: no stable API, see preview |
| `sort` | Copy the unsorted array, then sort it (unsigned order by high half, then low half). C# compares the halves as one `UInt128` (10–14% faster); Java has no 128-bit integer, so it compares two `long`s |
| `hashset` | Build a `HashSet` of all elements (both sides use the same hash function) |
| `parallel.equals` | `equals.scalar` split over all cores (`Parallel.For` / parallel `IntStream`, same chunking) |
| `parallel.equals.simd` | C# only: `equals.simd` split over all cores. At large sizes one core can't read memory fast enough to keep SIMD busy |
| `parallel.create.prng` | `create.prng` split over all cores |
| `handopt.sort.radix` | **Hand-optimized:** LSD radix sort, 16-bit digits, 8 passes, skipping passes where every element has the same digit. Same order as `sort`. C# moves the 16-byte structs; Java moves references and has to dereference each one to read its key |
| `handopt.hashset.open` | **Hand-optimized:** open-addressing hash set (linear probing, power-of-two table at most half full, Fibonacci hashing). Both store the 16 bytes inline in flat arrays, so probing never follows a reference |
| `memory.footprint` | Heap bytes per element of a filled array |

## Results

### Main race (JIT, stable features)

4-core x64 Linux cloud VM with AVX-512, .NET 11.0.0 vs OpenJDK 25.0.5 (compact object headers on). Median **ns per element**, fastest in bold. *Java tuned* is the extra column with the 4 GB young generation.

**1M elements**

| Case | C# | Java | Java tuned |
|---|---:|---:|---:|
| `create.zeroed` | **1.45** | 3.66 | 3.32 |
| `create.prng` | **1.61** | 6.18 | 4.71 |
| `create.guid` | 270.88 | 174.25 | **166.65** |
| `create.crypto-bulk` | **3.09** | 37.26 | 36.93 |
| `equals.scalar` | **1.14** | 1.36 | 1.25 |
| `equals.simd` | 0.57 | – | – |
| `sort` | **73.78** | 265.98 | 241.09 |
| `hashset` | **35.94** | 41.73 | 39.82 |
| `parallel.equals` | 0.57 | **0.45** | 0.48 |
| `parallel.equals.simd` | 0.43 | – | – |
| `parallel.create.prng` | **0.92** | 1.59 | 1.40 |

**10M elements**

| Case | C# | Java | Java tuned |
|---|---:|---:|---:|
| `create.zeroed` | **2.36** | 3.85 | 3.56 |
| `create.prng` | **2.23** | 5.10 | 4.62 |
| `create.guid` | 265.32 | 192.26 | **175.69** |
| `create.crypto-bulk` | **4.18** | 36.20 | 37.39 |
| `equals.scalar` | 2.72 | 1.99 | **1.94** |
| `equals.simd` | 1.74 | – | – |
| `sort` | **91.52** | 402.39 | 413.48 |
| `hashset` | 57.88 | **55.78** | 73.46 |
| `parallel.equals` | **0.64** | 0.84 | 0.95 |
| `parallel.equals.simd` | 0.39 | – | – |
| `parallel.create.prng` | **0.76** | 1.35 | 1.21 |

The 50k results are in `results/summary.md` after a run: everything fits in the CPU cache there, and Java wins `equals.scalar` and `hashset` at that size.

| | C# | Java |
|---|---:|---:|
| Memory per element | **16 bytes** | 28 bytes (36 without compact headers) |
| Heap allocated per created element | **0 bytes** | 24 bytes |

What stands out:

- **Sort** is the clearest win: about 3.6× (1M) and 4.4× (10M) faster in C#. Java follows a reference for every comparison and no JVM setting changes that.
- **Creating** values: C# writes 16 bytes into one flat block; Java allocates a 24-byte object per element. Compact headers and GC sizing brought Java much closer (10M `create.zeroed`: 15.9 ns before, 3.6–3.9 ns now), but C# still wins every creation case.
- **Bulk crypto fill:** about 9–12× faster in C#, even after switching Java to the faster `DRBG` algorithm (114 → 36 ns). The C# array is raw memory, so one OS call fills it.
- **SIMD across all cores** (`parallel.equals.simd`, C# only) is the fastest way to scan 10M elements: 0.39 ns, vs 1.74 ns for single-core SIMD, which can't read memory fast enough on its own.
- **Java wins** the built-in GUID (`UUID.randomUUID()` buffers its randomness; .NET asks the OS once per GUID on Linux), `equals.scalar` at 10M, `parallel.equals` at 1M, and `hashset` at 10M by a small margin.
- **Tuned GC:** a small gain at 1M, but at 10M it's mixed (`hashset` is slower: the huge young generation leaves less room for the long-lived set). In the full race the default Parallel GC already resizes its young generation after the first cases, so the effect is smaller than in a single-case test.

**How noisy is this?** The same unchanged C# binary measured `create.zeroed` at 1M as anywhere from 0.60 to 1.15 ns across three launches on this cloud VM. Treat differences under about 30% as noise; for firmer numbers run `./bench.sh` (BenchmarkDotNet + JMH, several forks) on a quiet machine.

### Hand-optimized track

From the GitHub Actions **main** job (ubuntu-24.04 runner, quick mode), JIT, ns per element. The standard-library
version of each case is shown for comparison:

| Case | 1M C# | 1M Java | 10M C# | 10M Java |
|---|---:|---:|---:|---:|
| `sort` (standard library) | **65.34** | 186.04 | **79.25** | 273.01 |
| `handopt.sort.radix` | **70.17** | 73.22 | **80.37** | 147.93 |
| `hashset` (standard library) | 19.75 | **18.99** | **33.93** | 35.36 |
| `handopt.hashset.open` | **14.83** | 16.75 | **23.93** | 27.32 |

- **Radix sort helps Java a lot** (1.8–2.5× faster than `Arrays.sort`) **but not C#**, where it ties `Array.Sort`.
  C#'s introsort on 16-byte structs is already cache-friendly, and the radix sort moves all 16 bytes of every element
  8 times. Java's standard sort is slow because of pointer chasing, so radix sort's sequential passes help it more,
  but Java still has to follow a reference to read each key.
- **Radix sort has a fixed cost** of clearing and scanning 8 × 65,536 counters per call, so it only pays off for large
  arrays (at 50k it's about 40–46 ns per element; at 1,000 elements it's hundreds).
- **The open-addressing hash set beats the standard one in both languages** (about 1.1–1.4×). Both versions store the
  16 bytes inline, so here the two languages are close; C# still wins at 1M and 10M.
- These numbers are from a different machine than the main race above; compare within this table.

### Native race (ahead-of-time compiled)

From the GitHub Actions **native** job (ubuntu-24.04 runner, quick mode), .NET 11 NativeAOT vs GraalVM Community 25.0.2 `native-image`, ns per element:

| Case | 1M C# | 1M Java | 10M C# | 10M Java |
|---|---:|---:|---:|---:|
| `create.zeroed` | **0.37** | 2.84 | **0.70** | 69.85 |
| `create.prng` | 7.35 | **4.28** | 7.31 | **4.40** |
| `create.guid` | 521.99 | **359.34** | 522.04 | **432.07** |
| `create.crypto-bulk` | **2.99** | 87.72 | **3.47** | 89.60 |
| `equals.scalar` | **0.91** | 1.88 | **1.06** | 2.13 |
| `equals.simd` | 0.40 | – | 0.79 | – |
| `sort` | **118.98** | 350.07 | **140.35** | 551.43 |
| `hashset` | **32.41** | 75.71 | **74.70** | 112.29 |
| `parallel.equals` | **0.51** | 0.74 | **0.46** | 1.16 |
| `parallel.equals.simd` | 0.23 | – | 0.38 | – |
| `parallel.create.prng` | 3.23 | **2.15** | 2.77 | **2.45** |
| Memory per element | **16.0 bytes** | 48.4 bytes | | |

- GraalVM Community's native images use the Serial GC (hence 69.85 ns for `create.zeroed` at 10M: GC copies the objects) and can't report allocations. Compact object headers are a HotSpot feature, so they don't apply here. Oracle GraalVM adds G1 and profile-guided optimisation, which would narrow the gap.
- **GitHub runners differ from run to run.** On the runner for this run, NativeAOT's `create.prng` took 7.3 ns; the same build takes 2.2 ns on the main-race VM, and an earlier CI run measured 1.9 ns. Compare numbers within one table, not across tables or runs.

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

Result with build `27-jep401ea3+1-1` (preview job, same JVM flags as the main race including compact headers, ns per element):

| Case | 50k | 1M | 10M |
|---|---:|---:|---:|
| `create.zeroed` | 2.08 | 2.02 | 2.35 |
| `create.prng` | 3.28 | 3.34 | 3.41 |
| `equals.scalar` | 0.58 | 1.34 | 1.91 |
| `sort` | 113.14 | 197.20 | 351.08 |
| `hashset` | 10.85 | 29.42 | 146.04 |

| | Java `record` | Valhalla `value record` |
|---|---:|---:|
| Heap allocated per created element | 24 bytes | 32 bytes |
| Memory per element | 28 bytes | 36 bytes |

**This build did not flatten the array.** Every value was still allocated as its own heap object, and each one is
8 bytes bigger than a normal record. That matches the JEP 401 rules: a nullable 16-byte value needs a null marker and
atomic updates, which is more than the JVM can flatten today. Valhalla doesn't make Java struct-like for this data yet;
re-run the preview job when a new early-access build comes out.

## Notes

- **BenchmarkDotNet + .NET 11:** BenchmarkDotNet 0.15.8 (the latest release) doesn't recognise .NET 11 yet, so `StructSpeedTest.Benchmarks` runs in-process (`--inProcess`). Remove that once a release supports `net11.0`.
- **NativeAOT and SIMD:** NativeAOT targets a baseline x64 CPU by default, which turns AVX2/AVX-512 off. `native.sh` passes `IlcInstructionSet=native` (and GraalVM gets `-march=native`) so both sides use the build machine's CPU.
- **C# 15:** the project uses C# 15, but its new features (unions, closed hierarchies, labeled `break`/`continue`, extension indexers, collection expression arguments) are about expressiveness, not speed, so none of them are used here.
- Numbers depend on CPU, OS and runtime versions. Run it on your own machine before drawing conclusions.
