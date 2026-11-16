# Shared settings for the run scripts (sourced, not executed).
# JVM flags for every Java run: fixed 4 GB heap (10M-element HashSets need it) and the throughput collector,
# the closest match to the .NET non-concurrent workstation GC used on the C# side.
export JAVA_BENCH_OPTS="${JAVA_BENCH_OPTS:--XX:+UseParallelGC -Xms4g -Xmx4g -XX:+AlwaysPreTouch}"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1

# Usage: require dotnet java mvn python3
require() {
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 1; }
  done
}

require java
java_feature=$(java -XshowSettings:properties -version 2>&1 | awk '/java.specification.version/ {print $3}')
if [ "${java_feature%%.*}" -lt 25 ] 2>/dev/null; then
  echo "Java $java_feature found on PATH; JDK 25 or newer is required." >&2
  exit 1
fi
