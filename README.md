# Struct-Speed-Test

Measures how fast C# structs are created and compared, raced against the equivalent Java code.

- `Data.cs` – the struct: 16 bytes stored inline as two `ulong` fields (no `byte[]`, no heap allocation per instance).
- `Program.cs` – the C# benchmark harness.
- `java/StructSpeedTest.java` – the Java counterpart: same cases, same element count, same harness.
- `race.sh` – builds and runs both back to back.

## Running

Requires the .NET 10 SDK and JDK 21+.

```bash
./race.sh            # 50,000 elements (default)
./race.sh 1000000    # custom element count
```

Or individually:

```bash
dotnet run -c Release -- 50000
javac -d java/out java/StructSpeedTest.java && java -XX:+UseParallelGC -Xms1g -Xmx1g -cp java/out StructSpeedTest 50000
```

Always use a Release build; Debug numbers are meaningless.

## How it measures

For each case, both programs:

1. Warm up for at least 30 rounds **and** 1 second, so the JIT (RyuJIT tiered compilation + PGO / HotSpot C2) has produced fully optimized code.
2. Time 50 rounds over the whole array and report the **median** and **best** round, plus nanoseconds per element.
3. Add every result into a printed checksum, so the compiler can't remove the work as dead code.

## Cases

| Case | What it measures |
|---|---|
| Create zeroed | Filling the array with `new Data(0, 0)` |
| Create from PRNG (SplitMix64) | Creating each element from a fast deterministic random generator (same algorithm in both languages) |
| Create from `Guid.NewGuid()` / `UUID.randomUUID()` | Each language's built-in random GUID, one call per element |
| Create from crypto RNG (bulk) | One call to the OS crypto RNG for all the bytes, then turned into elements |
| Equals: first vs all | Compares element 0 against every element through the normal `Equals` |
| Equals: first vs all (SIMD) | C# only: `Data.CountMatches` compares 2 (AVX2) or 4 (AVX-512) structs per CPU instruction |

## Sample results

50,000 elements, 4-core x64 Linux cloud VM, .NET 10.0 vs OpenJDK 21. Median ns per element:

| Case | C# | Java |
|---|---:|---:|
| Create zeroed | **0.31** | 4.06 |
| Create from PRNG (SplitMix64) | **1.50** | 3.69 |
| Create from built-in GUID | 286.65 | **191.62** |
| Create from crypto RNG (bulk) | **2.31** | 113.84 |
| Equals: first vs all | 0.55–0.78* | 0.57 |
| Equals: first vs all (SIMD) | **0.24** | n/a |

\* The scalar loop moves between ~0.55 and ~0.78 ns depending on where the JIT places the machine code (loop alignment), not on the code itself.

### Why the numbers look like this

- **Creation:** a C# struct array is one contiguous block, so "creating" an element is just writing 16 bytes. Every Java `new Data(...)` is a heap object (header + fields) plus a pointer in the array, and the GC has to clean it up later.
- **Built-in GUID:** on Linux, .NET's `Guid.NewGuid()` asks the OS for random bytes on every call, while Java's `SecureRandom` buffers. If you just need random 128-bit values, the bulk crypto RNG case is ~125× faster in C#. Because the struct array is raw memory, it can be filled with a single `RandomNumberGenerator.Fill` call.
- **Comparison:** both scalar loops are around half a nanosecond per element. Because structs are packed next to each other in memory, C# can also compare them with SIMD; Java's object array is an array of pointers, so it can't.

Numbers depend on the CPU, OS and runtime versions, so run it on your own machine.
For publication-grade numbers, use [BenchmarkDotNet](https://benchmarkdotnet.org/) and [JMH](https://github.com/openjdk/jmh).
