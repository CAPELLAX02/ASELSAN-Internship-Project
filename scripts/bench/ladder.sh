#!/usr/bin/env bash
# Rate ladder for the DKM gateway, run against the native binary in a pinned
# container.
#
# What this measures, and why it is shaped this way:
#
#   The question is not "how fast can it go". A replay tool that moves a
#   gigabyte a second and does not reproduce the recording it was given is
#   wrong, just quickly. So each level offers a FIXED rate and the measurement
#   is how faithfully that rate came out the other side. The rate itself is
#   carried by the stimulus file -- every message has the time it was recorded
#   at, and the pacer sends it when its clock gets there -- so nothing in the
#   production code knows a benchmark is running, and no limiter sits in the
#   path being measured.
#
#   Lateness is measured by the peer, from the wire, against the timestamps in
#   the headers. It counts every message including the ones that arrive during a
#   stall, which is the omission that makes most latency numbers flattering.
#
#   Gateway and peer share one container so the loopback in the measurement is
#   the container's own, and one cgroup so the CPU and memory the run was given
#   are the CPU and memory the numbers belong to.
#
# Usage: scripts/bench/ladder.sh [--levels "10000 100000"] [--runs 3]
#                               [--cpus 8] [--memory 8g] [--heap 6g]
#                               [--out results/ladder] [--gc-level 500000]
#                               [--skip-build]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LEVELS="10000 50000 100000 250000 500000 1000000"
RUNS=3
CPUS=8
MEMORY=8g
HEAP=6g
OUT="$ROOT/benchmark/ladder"
GC_LEVEL=500000
SKIP_BUILD=0
IMAGE=dkm-bench:latest

while [[ $# -gt 0 ]]; do
  case "$1" in
    --levels) LEVELS="$2"; shift 2 ;;
    --runs)   RUNS="$2";   shift 2 ;;
    --cpus)   CPUS="$2";   shift 2 ;;
    --memory) MEMORY="$2"; shift 2 ;;
    --heap)   HEAP="$2";   shift 2 ;;
    --out)    OUT="$2";    shift 2 ;;
    --gc-level) GC_LEVEL="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    *) echo "bilinmeyen secenek: $1"; exit 1 ;;
  esac
done

need() { command -v "$1" >/dev/null || { echo "PATH uzerinde $1 gerekli"; exit 1; }; }
need docker
need python3

RUNNER="$ROOT/dkm-gateway/target/dkm-gateway-1.0.0-SNAPSHOT-runner"

if [[ $SKIP_BUILD -eq 0 || ! -f "$RUNNER" ]]; then
  echo "==> native ikili derleniyor (konteynerde, birkac dakika surer)"
  ( cd "$ROOT/dkm-gateway" && ./mvnw -B package -Dnative -DskipTests \
      -Dquarkus.native.container-build=true ) || {
    echo "native derleme basarisiz"; exit 1; }
fi
[[ -f "$RUNNER" ]] || { echo "$RUNNER yok"; exit 1; }

# The image only ever contains files this directory owns plus the runner, so the
# build context is assembled rather than pointing docker at the repository root.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$RUNNER" "$STAGE/gateway-runner"
cp "$ROOT/scripts/bench/Bench.java" "$ROOT/scripts/bench/ladder-run.sh" \
   "$ROOT/scripts/bench/sample-proc.py" "$ROOT/scripts/bench/Dockerfile.bench" "$STAGE/"

echo "==> olcum imaji derleniyor"
docker build -q -f "$STAGE/Dockerfile.bench" -t "$IMAGE" "$STAGE" >/dev/null

mkdir -p "$OUT" "$OUT/gc"
rm -f "$OUT"/*.json "$OUT"/*.hgrm "$OUT"/run.log "$OUT"/gc/* 2>/dev/null || true

echo "==> merdiven kosuluyor: [$LEVELS] x $RUNS, ${CPUS} CPU / ${MEMORY} bellek"
docker run --rm \
  --cpus="$CPUS" --memory="$MEMORY" \
  -e LEVELS="$LEVELS" -e RUNS="$RUNS" -e HEAP="$HEAP" \
  -v "$OUT:/out" \
  "$IMAGE"

# One extra pass with the collector talking, so the tail in the table above can
# be attributed rather than guessed at. Separate from the ladder because the
# logging itself lands on the hot path, and the ladder's numbers should not
# carry it.
if [[ -n "$GC_LEVEL" ]]; then
  echo "==> $GC_LEVEL msg/s bir kez daha, bu sefer GC kaydiyla"
  docker run --rm \
    --cpus="$CPUS" --memory="$MEMORY" \
    -e LEVELS="$GC_LEVEL" -e RUNS=1 -e HEAP="$HEAP" -e GCLOG=1 \
    -v "$OUT/gc:/out" \
    "$IMAGE" >/dev/null
fi

echo "==> rapor uretiliyor"
python3 "$ROOT/scripts/bench/report.py" "$OUT" \
        --image "$IMAGE" --cpus "$CPUS" --memory "$MEMORY" \
        --out "$ROOT/benchmark-ladder.md"
echo "    $ROOT/benchmark-ladder.md"
