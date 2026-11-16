#!/usr/bin/env bash
# Builds and runs the C# and Java benchmarks back to back.
# Usage: ./race.sh [element-count]
set -euo pipefail
cd "$(dirname "$0")"
COUNT="${1:-50000}"

echo "== Building =="
dotnet build -c Release -nologo -v quiet TestingSpeed.csproj >/dev/null
mkdir -p java/out
javac -d java/out java/StructSpeedTest.java

echo
echo "== C# =="
dotnet bin/Release/net10.0/TestingSpeed.dll "$COUNT"

echo
echo "== Java =="
java -XX:+UseParallelGC -Xms1g -Xmx1g -XX:+AlwaysPreTouch -cp java/out StructSpeedTest "$COUNT"
