#!/usr/bin/env bash
# Main race: C# (.NET 11, JIT) vs Java (JDK 25, HotSpot) using stable features only.
# Java runs twice: with the default settings (tools/env.sh JAVA_BENCH_OPTS) and with a tuned GC (JAVA_TUNED_OPTS).
# Usage: ./race.sh [--quick] [--sizes 50000,1000000,10000000] [--filter text]
# Writes results/csharp.csv, results/java.csv, results/java-tuned.csv and results/summary.md.
set -euo pipefail
cd "$(dirname "$0")"
source tools/env.sh
require dotnet mvn python3

echo "== Building"
dotnet build csharp/StructSpeedTest/StructSpeedTest.csproj -c Release -nologo -v quiet >/dev/null
(cd java && mvn -q -B package -DskipTests)

mkdir -p results
echo
echo "== C#"
dotnet csharp/StructSpeedTest/bin/Release/net11.0/StructSpeedTest.dll --csv results/csharp.csv "$@"

echo
echo "== Java"
java $JAVA_BENCH_OPTS -cp java/target/benchmarks.jar structspeed.Main --csv results/java.csv "$@"

echo
echo "== Java (tuned GC)"
java $JAVA_TUNED_OPTS -cp java/target/benchmarks.jar structspeed.Main --csv results/java-tuned.csv --label "tuned GC" "$@"

python3 tools/compare.py results/csharp.csv results/java.csv results/java-tuned.csv > results/summary.md
echo
echo "== Summary written to results/summary.md"
cat results/summary.md
