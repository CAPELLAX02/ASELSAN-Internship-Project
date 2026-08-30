#!/usr/bin/env bash
# End-to-end demo: gateway, console and mock_r, started in the order the topology
# requires. mock_r connects out exactly once at its own startup and never retries, so
# the gateway has to be listening before it runs -- that ordering is the whole reason
# this script exists rather than three README lines.
#
# Usage: scripts/demo.sh [speed]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEED="${1:-1.0}"
GATEWAY="http://127.0.0.1:8080"
WORK="$(mktemp -d)"
PIDS=()

# Each service runs inside a subshell, so the thing we recorded is the subshell
# and the thing holding the port is its child (java, or npm's vite). Killing
# only what we recorded leaves the ports bound and the next run fails to start.
kill_tree() {
  local pid=$1
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}

cleanup() {
  echo
  echo "stopping..."
  for pid in "${PIDS[@]:-}"; do kill_tree "$pid"; done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

need() { command -v "$1" >/dev/null || { echo "need $1 on PATH"; exit 1; }; }
need curl
need node

MOCK_R="$ROOT/dkm-simulator/mock_r/build/mock_r"
BIN_GEN="$ROOT/dkm-simulator/bin_gen/build/bin_gen"
[[ -x "$MOCK_R" ]] || { echo "mock_r is not built: cmake -S dkm-simulator/mock_r -B dkm-simulator/mock_r/build && cmake --build dkm-simulator/mock_r/build"; exit 1; }

# The checked-in sample by default, so a demo needs nothing built beyond mock_r.
# bin_gen only comes into it when a different scenario is asked for.
SCENARIO="${SCENARIO:-$ROOT/samples/radar-demo-180s.in.bin}"
if [[ ! -r "$SCENARIO" ]]; then
  [[ -x "$BIN_GEN" ]] || { echo "no $SCENARIO, and bin_gen is not built: cmake -S dkm-simulator/bin_gen -B dkm-simulator/bin_gen/build && cmake --build dkm-simulator/bin_gen/build"; exit 1; }
  echo "==> generating a stimulus binary"
  "$BIN_GEN" "$WORK/scenario.bin" --seconds 180 --targets 24
  SCENARIO="$WORK/scenario.bin"
fi
echo "==> stimulus: $SCENARIO"

echo "==> starting the gateway"
( cd "$ROOT/dkm-gateway" && ./mvnw -q -B package -DskipTests \
    && java -jar target/quarkus-app/quarkus-run.jar ) > "$WORK/gateway.log" 2>&1 &
PIDS+=($!)

printf "    waiting for it to bind"
for _ in $(seq 1 90); do
  sleep 1; printf "."
  if curl -sf "$GATEWAY/api/status" >/dev/null 2>&1; then echo " up"; break; fi
done
curl -sf "$GATEWAY/api/status" >/dev/null || { echo; echo "gateway did not start -- see $WORK/gateway.log"; exit 1; }

echo "==> loading the scenario"
curl -s -X POST "$GATEWAY/api/session/load-path" \
     -H 'Content-Type: application/json' -d "{\"path\":\"$SCENARIO\"}"
echo
curl -s -X PUT "$GATEWAY/api/playback/speed" \
     -H 'Content-Type: application/json' -d "{\"speed\":$SPEED}" >/dev/null

echo "==> starting the console"
( cd "$ROOT/dkm-console" && { [[ -d node_modules ]] || npm install; } && npm run dev ) \
  > "$WORK/console.log" 2>&1 &
PIDS+=($!)

echo "==> starting mock_r (it connects out once, now that the ports are bound)"
mkdir -p "$WORK/mock"
( cd "$WORK/mock" && "$MOCK_R" ) > "$WORK/mock_r.log" 2>&1 &
PIDS+=($!)
sleep 2

curl -s "$GATEWAY/api/status/links" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>JSON.parse(s).forEach(l=>console.log(`    ${l.name}: ${l.state}`)))'

echo
echo "Console:  $(grep -o 'http://localhost:[0-9]*' "$WORK/console.log" | head -1 || echo http://localhost:5173)"
echo "Gateway:  $GATEWAY"
echo "Logs:     $WORK"
echo
echo "Press Start in the console, or:  curl -X POST $GATEWAY/api/playback/start"
echo "Ctrl-C to stop everything."
wait
