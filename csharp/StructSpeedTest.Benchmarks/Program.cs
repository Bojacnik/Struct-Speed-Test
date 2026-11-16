using BenchmarkDotNet.Running;

// Examples:
//   dotnet run -c Release                                  (interactive menu)
//   dotnet run -c Release -- --filter '*'                  (everything)
//   dotnet run -c Release -- --filter '*Equals*' --job short
//
// BenchmarkDotNet 0.15.x does not recognise .NET 11 yet (its SDK check throws), so benchmarks run
// in-process: same runtime, same GC settings, just without a separate child process per benchmark.
// Remove this once a BenchmarkDotNet release supports net11.0.
string[] effectiveArgs = args.Contains("--inProcess", StringComparer.OrdinalIgnoreCase) ? args : [.. args, "--inProcess"];
BenchmarkSwitcher.FromAssembly(typeof(Program).Assembly).Run(effectiveArgs);
