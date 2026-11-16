# shellcheck shell=bash
# Shared settings for the run scripts (sourced, not executed).
# JVM flags for every Java run:
# - fixed 4 GB heap (10M-element HashSets need it)
# - Parallel GC: the throughput collector, closest to the non-concurrent workstation GC on the C# side
# - compact object headers (JEP 519, a stable feature since JDK 25): objects shrink from 32 to 24 bytes here
export JAVA_BENCH_OPTS="${JAVA_BENCH_OPTS:--XX:+UseParallelGC -Xms4g -Xmx4g -XX:+AlwaysPreTouch -XX:+UseCompactObjectHeaders}"

# "Tuned GC" Java column: a 4 GB young generation, so short-lived benchmark rounds rarely trigger a GC that
# copies millions of still-referenced objects. This mostly postpones GC work rather than removing it, which is
# why it is reported as a separate column next to the default settings.
export JAVA_TUNED_OPTS="${JAVA_TUNED_OPTS:--XX:+UseParallelGC -Xms6g -Xmx6g -Xmn4g -XX:+AlwaysPreTouch -XX:+UseCompactObjectHeaders}"
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

# Maven compiles with JAVA_HOME, which can point at an older JDK than the java on PATH: use the same JDK for both.
JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home/ {print $2}')"
export JAVA_HOME
