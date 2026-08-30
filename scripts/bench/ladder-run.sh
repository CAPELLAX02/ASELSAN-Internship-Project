#!/usr/bin/env bash
# Runs inside the benchmark container. Walks a ladder of offered message rates
# and writes one JSON object per level per run to /out.
#
# The rate is not enforced by a limiter in the gateway. It comes from the
# stimulus file itself: every message carries the time it was recorded at, and
# the pacer sends it when its clock reaches that time. So "offered rate" is a
# property of the input, the gateway's job is to reproduce it, and the peer
# measures independently whether it did. Nothing in the production code knows a
# benchmark is running.
set -euo pipefail

OUT=${OUT:-/out}
LEVELS=${LEVELS:-"10000 50000 100000 250000 500000 1000000"}
RUNS=${RUNS:-3}
TARGET_MESSAGES=${TARGET_MESSAGES:-8000000}
MIN_SECONDS=${MIN_SECONDS:-10}
MAX_SECONDS=${MAX_SECONDS:-30}
HEAP=${HEAP:-6g}
WORK=/tmp/bench
GATEWAY=http://127.0.0.1:8080

mkdir -p "$OUT" "$WORK"
: > "$OUT/run.log"
log() { echo "$*" | tee -a "$OUT/run.log"; }

api()  { curl -s --max-time 30 "$GATEWAY$1"; }
post() { curl -s --max-time 60 -X POST "$GATEWAY$1"; }
put()  { curl -s --max-time 30 -X PUT "$GATEWAY$1" -H 'Content-Type: application/json' -d "$2"; }
jqp()  { python3 -c "import json,sys;d=json.load(sys.stdin);print($1)"; }

# --- environment, recorded so the numbers can be argued with ---------------
cpus=$(nproc)
memlimit=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo unknown)
cpuquota=$(cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo unknown)
mtu=$(cat /sys/class/net/lo/mtu 2>/dev/null || echo unknown)
python3 - "$OUT/environment.json" <<PY
import json, platform, subprocess, sys
model = ""
try:
    model = open("/proc/cpuinfo").read().split("\n")[0]
except Exception:
    pass
json.dump({
  "kernel": platform.release(), "machine": platform.machine(),
  "cpus": $cpus, "cgroupMemoryMax": "$memlimit", "cgroupCpuMax": "$cpuquota",
  "loopbackMtu": "$mtu", "cpuinfoFirstLine": model,
  "gatewayFlags": "-Xmx$HEAP",
}, open(sys.argv[1], "w"), indent=1)
PY
log "== ortam: $cpus CPU, cgroup bellek $memlimit, lo MTU $mtu"

for rate in $LEVELS; do
  seconds=$(python3 -c "print(max($MIN_SECONDS, min($MAX_SECONDS, int($TARGET_MESSAGES/$rate))))")
  bytes=$(python3 -c "print(int($rate*$seconds*170))")   # ~170 B per message in this mix
  log ""
  log "== seviye ${rate} msg/s, ${seconds} s"
  # Written once and reused by every run of this level. Regenerating per run was
  # tried and is worse: a gigabyte of dirty pages being written back lands on
  # the very measurement that follows it.
  java /bench/Bench.java gen "$WORK/level.bin" "$bytes" "$rate" 2>&1 \
      | sed 's/^/   /' | tee -a "$OUT/run.log"

  # Push the file to disk and let writeback settle before anything is timed.
  # Without this the first run of every level carries the cost of flushing
  # however many hundred megabytes were just written, and the run reads as a
  # gateway latency spike that the gateway had no part in. The effect scales
  # with file size, which is why it only ever showed up at the high rates.
  sync
  sleep "${SETTLE_SECONDS:-5}"

  for run in $(seq 1 "$RUNS"); do
    tag="${rate}-run${run}"
    log "   -- kosu $run/$RUNS"

    # Only -Xmx here. A native image bakes its collector in at build time and
    # rejects the JVM's -XX: flags outright, so anything else would not be a
    # different configuration, it would be a gateway that failed to start.
    # GCLOG=1 asks the image to print every collection. Off by default because
    # the printing itself lands on the hot path; on when the question is whether
    # a latency tail is the collector rather than the network.
    GCFLAGS=""
    [[ "${GCLOG:-0}" == "1" ]] && GCFLAGS="-XX:+PrintGC -XX:+VerboseGC"
    /bench/gateway -Dquarkus.http.host=127.0.0.1 -Xmx"$HEAP" $GCFLAGS \
        > "$WORK/gateway-$tag.log" 2>&1 &
    gwpid=$!
    for _ in $(seq 1 120); do api /api/status >/dev/null 2>&1 && break; sleep 0.25; done
    api /api/status >/dev/null || { log "      gateway acilmadi"; cat "$WORK/gateway-$tag.log" >> "$OUT/run.log"; kill $gwpid 2>/dev/null; continue; }

    java /bench/Bench.java peer 127.0.0.1 5001 5002 5003 \
         --until-idle 3 --report-ms 2000 --quiet \
         --hgrm "$OUT/deviation-$tag.hgrm" --json "$WORK/peer-$tag.json" \
         > "$WORK/peer-$tag.log" 2>&1 &
    peerpid=$!
    sleep 2

    loaded=$(curl -s -X POST "$GATEWAY/api/session/load-path" \
             -H 'Content-Type: application/json' -d "{\"path\":\"$WORK/level.bin\"}")
    put /api/playback/mode '{"mode":"TIMESTAMP"}' >/dev/null
    put /api/playback/speed '{"speed":1.0}' >/dev/null

    # Sample the gateway's own cost while it runs, from its own cgroup-visible
    # process rather than a whole-container figure that would also count the
    # load generator sitting next to it.
    python3 /bench/sample-proc.py $gwpid "$WORK/proc-$tag.txt" &
    samplerpid=$!

    before=$(api /q/metrics || true)
    post /api/playback/start >/dev/null
    while true; do
      state=$(api /api/playback | jqp "d.get('state')" 2>/dev/null || echo UNKNOWN)
      [[ "$state" == "FINISHED" || "$state" == "UNKNOWN" ]] && break
      sleep 1
    done
    lag=$(api /api/playback | jqp "d.get('lagMillis',0)")
    after=$(api /q/metrics || true)
    printf '%s' "$before" > "$WORK/metrics-before-$tag.txt"
    printf '%s' "$after"  > "$WORK/metrics-after-$tag.txt"
    links=$(api /api/status/links)

    wait $peerpid 2>/dev/null || true
    kill $samplerpid 2>/dev/null || true
    kill $gwpid 2>/dev/null || true
    wait $gwpid 2>/dev/null || true
    [[ "${GCLOG:-0}" == "1" ]] && cp "$WORK/gateway-$tag.log" "$OUT/gc-$tag.log"

    python3 - "$rate" "$run" "$seconds" "$lag" "$WORK/peer-$tag.json" \
             "$WORK/metrics-after-$tag.txt" "$WORK/proc-$tag.txt" "$OUT/level-$tag.json" \
             <<'PY'
import json, sys
rate, run, seconds, lag, peerfile, metricsfile, procfile, out = sys.argv[1:]
peer = json.load(open(peerfile))
metrics = {}
for line in open(metricsfile, errors="ignore"):
    if line.startswith("#") or " " not in line: continue
    name, _, value = line.rpartition(" ")
    try: metrics[name.strip()] = float(value)
    except ValueError: pass
def m(prefix):
    return sum(v for k, v in metrics.items() if k.startswith(prefix))
ticks, rss = 0, 0
try:
    rows = [l.split() for l in open(procfile) if len(l.split()) == 2]
    if rows:
        ticks = int(rows[-1][0]) - int(rows[0][0])
        rss = max(int(r[1]) for r in rows)
except Exception:
    pass
json.dump({
  "offeredRate": int(rate), "run": int(run), "seconds": float(seconds),
  "peer": peer,
  "gateway": {
    "finalLagMillis": float(lag),
    "writeStalls": m('dkm_link_write_stalls'),
    "vizFramesSkipped": m('dkm_viz_frames_skipped'),
    "vizSamplesDropped": m('dkm_viz_dropped'),
    "captureOverflowed": m('dkm_capture_overflowed'),
    "eventsDropped": m('dkm_events_dropped'),
    "cpuSeconds": ticks / 100.0,
    "peakRssKb": rss,
  },
}, open(out, "w"), indent=1)
print(f"      {peer['bytesPerSecond']/1048576:.0f} MiB/s, {peer['messagesPerSecond']:,.0f} msg/s, "
      f"sapma p99 {peer['jitterMicros']['p99']/1000:.3f} ms, "
      f"CPU {ticks/100.0:.1f} s, RSS {rss//1024} MiB")
PY
  done
  rm -f "$WORK/level.bin"
done

log ""
log "== bitti, ciktilar $OUT altinda"
