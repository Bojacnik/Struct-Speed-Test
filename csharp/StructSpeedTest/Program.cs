using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Intrinsics;

namespace StructSpeedTest;

/// <summary>
/// Quick C# vs Java race. The Java counterpart is java/src/main/java/structspeed/Main.java.
/// Usage: StructSpeedTest [--sizes 50000,1000000,10000000] [--csv results/csharp.csv] [--filter text] [--quick]
/// </summary>
internal static class Program
{
    private static readonly int[] DefaultSizes = [50_000, 1_000_000, 10_000_000];
    private const int FootprintSize = 1_000_000;

    public static int Main(string[] args)
    {
        var sizes = DefaultSizes;
        string? csvPath = null, filter = null;
        var quick = false;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--sizes": sizes = [.. args[++i].Split(',').Select(s => int.Parse(s.Replace("_", "")))]; break;
                case "--csv": csvPath = args[++i]; break;
                case "--filter": filter = args[++i]; break;
                case "--quick": quick = true; break;
                default:
                    Console.Error.WriteLine($"Unknown argument '{args[i]}'. Usage: [--sizes a,b,c] [--csv path] [--filter text] [--quick]");
                    return 1;
            }
        }

        var runtime = $".NET {Environment.Version}{(RuntimeFeature.IsDynamicCodeSupported ? "" : " NativeAOT")}";
        Console.WriteLine($"C# | {runtime} | {RuntimeInformation.OSArchitecture} | {Environment.ProcessorCount} cores | " +
                          $"SIMD {(Vector512.IsHardwareAccelerated ? "AVX-512" : Vector256.IsHardwareAccelerated ? "AVX2" : "none")}");

        var harness = new Harness(runtime, quick);
        foreach (var size in sizes)
        {
            var data = new Data[size];
            var sortWork = new Data[size];
            harness.PrintSizeHeader(size);

            void Case(string name, Func<long> body)
            {
                if (filter is null || name.Contains(filter, StringComparison.OrdinalIgnoreCase))
                    harness.Run(name, size, body);
            }

            Case("create.zeroed", () => Workloads.CreateZeroed(data));
            Case("create.prng", () => Workloads.CreateRandom(data));
            Case("create.guid", () => Workloads.CreateFromGuid(data));
            Case("create.crypto-bulk", () => Workloads.CreateFromCryptoRng(data));

            Workloads.CreateRandom(data); // deterministic contents for the remaining cases
            Case("equals.scalar", () => Workloads.CountMatches(data));
            Case("equals.simd", () => Workloads.CountMatchesSimd(data));
            Case("sort", () => Workloads.CopyAndSort(data, sortWork));
            Case("hashset", () => Workloads.BuildHashSet(data));
            Case("parallel.equals", () => Workloads.ParallelCountMatches(data));
            Case("parallel.equals.simd", () => Workloads.ParallelCountMatchesSimd(data));
            Case("parallel.create.prng", () => Workloads.ParallelCreateRandom(data));
        }

        if (filter is null || "memory.footprint".Contains(filter, StringComparison.OrdinalIgnoreCase))
            harness.Footprint(FootprintSize);

        if (csvPath is not null)
            harness.WriteCsv(csvPath);
        Console.WriteLine($"checksum: {harness.Sink}");
        return 0;
    }
}
