#!/usr/bin/env bash
# Publication-grade runs with the standard tools: BenchmarkDotNet (C#) and JMH (Java).
# Slow: expect 20+ minutes for everything. Extra arguments go to both tools' filters, e.g. ./bench.sh Equals
set -euo pipefail
cd "$(dirname "$0")"
source tools/env.sh
require dotnet mvn
filter="${1:-}"

echo "== BenchmarkDotNet"
(cd csharp/StructSpeedTest.Benchmarks && dotnet run -c Release -- --filter "*${filter}*" --artifacts ../../results/benchmarkdotnet)

echo
echo "== JMH"
(cd java && mvn -q -B package -DskipTests)
mkdir -p results/jmh
# (?i): JMH filters are case-sensitive regexes, and Java method names are camelCase (equalsScalar vs EqualsScalar).
java -jar java/target/benchmarks.jar "(?i)StructBenchmarks.*${filter}" -prof gc -rf json -rff results/jmh/results.json
