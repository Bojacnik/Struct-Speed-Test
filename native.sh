#!/usr/bin/env bash
# Ahead-of-time compiled race: .NET NativeAOT vs GraalVM native-image (stable features only).
# Needs clang + zlib headers for NativeAOT, and GraalVM's native-image on PATH for the Java side
# (skipped with a message if missing).
# Usage: ./native.sh [--quick] [--sizes ...] [--filter text]
set -euo pipefail
cd "$(dirname "$0")"
source tools/env.sh
require dotnet python3
mkdir -p results native

echo "== C# NativeAOT (targeting this machine's CPU, so AVX2/AVX-512 are available)"
dotnet publish csharp/StructSpeedTest/StructSpeedTest.csproj -c Release -r linux-x64 -nologo -v quiet \
  -p:PublishAot=true -p:IlcInstructionSet=native -o native/csharp >/dev/null
native/csharp/StructSpeedTest --csv results/csharp-aot.csv "$@"
files=(results/csharp-aot.csv)

if command -v native-image >/dev/null 2>&1; then
  echo
  echo "== Java GraalVM native-image (-march=native)"
  require mvn
  (cd java && mvn -q -B package -DskipTests)
  native-image -O3 -march=native --no-fallback -cp java/target/benchmarks.jar -o native/java-struct-speed structspeed.Main >/dev/null
  native/java-struct-speed --csv results/java-native.csv "$@"
  files+=(results/java-native.csv)
else
  echo
  echo "native-image not found: skipping the GraalVM side (install GraalVM 25 and put its bin/ on PATH)."
fi

python3 tools/compare.py "${files[@]}" > results/summary-native.md
echo
cat results/summary-native.md
