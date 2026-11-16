#!/usr/bin/env bash
# PREVIEW races - Java features that are not stable yet. Kept out of the main race on purpose.
#   ./preview.sh vector     Vector API (jdk.incubator.vector), JDK 25+ java on PATH
#   ./preview.sh valhalla   Value objects (JEP 401), needs VALHALLA_HOME pointing at a Valhalla early-access JDK
set -euo pipefail
cd "$(dirname "$0")"
source tools/env.sh
require mvn
mkdir -p results
(cd java && mvn -q -B package -DskipTests)

case "${1:-}" in
  vector)
    java $JAVA_BENCH_OPTS --add-modules jdk.incubator.vector -cp java/target/benchmarks.jar \
      java-preview/vector/VectorRace.java results/java-vector-preview.csv
    ;;
  valhalla)
    : "${VALHALLA_HOME:?Set VALHALLA_HOME to a Valhalla early-access JDK (https://jdk.java.net/valhalla/)}"
    feature=$("$VALHALLA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version/ {print $3}')
    "$VALHALLA_HOME/bin/java" $JAVA_BENCH_OPTS --enable-preview --source "$feature" -cp java/target/benchmarks.jar \
      java-preview/valhalla/ValhallaRace.java results/java-valhalla-preview.csv
    ;;
  *)
    echo "Usage: $0 vector|valhalla" >&2
    exit 1
    ;;
esac
