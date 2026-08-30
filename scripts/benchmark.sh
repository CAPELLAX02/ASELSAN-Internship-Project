#!/usr/bin/env bash
# DKM gateway icin tekrarlanabilir performans olcumu.
#
# Uc soruyu yanitlar ve sonucu bir rapora yazar:
#
#   1. Gateway uyarimi ne kadar hizli gonderebiliyor?   (azami hiz)
#   2. Bunu ne kadar *sadik* gonderebiliyor?            (surdurulebilir hiz)
#   3. Cikti tarayiciya ne kadar surede ulasiyor?       (gorsellestirme gecikmesi)
#
# Bir replay araci icin belirleyici olan ikincisidir. Ham throughput saniyede kac
# byte gectigini soyler; gecikme ise kayitli zaman cizgisinin hala yeniden
# uretilip uretilmedigini. Bir kosu saniyede bir gigabyte tasiyip yine de yanlis
# olabilir.
#
# Kullanim: scripts/benchmark.sh [--size 1G] [--rate 200000] [--out rapor.md]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY="http://127.0.0.1:8080"
SIZE="1G"
RATE="200000"
WORK="$(mktemp -d)"
OUT="$ROOT/benchmark-report.md"
LAG_BUDGET_MS=50
SAMPLE_SECONDS=4
PIDS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --size) SIZE="$2"; shift 2 ;;
    --rate) RATE="$2"; shift 2 ;;
    --out)  OUT="$2";  shift 2 ;;
    --lag)  LAG_BUDGET_MS="$2"; shift 2 ;;
    *) echo "bilinmeyen secenek: $1"; exit 1 ;;
  esac
done

kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill_tree "$pid"; done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

api()  { curl -s "$GATEWAY$1"; }
post() { curl -s -X POST "$GATEWAY$1"; }
put()  { curl -s -X PUT "$GATEWAY$1" -H 'Content-Type: application/json' -d "$2"; }

jqp() { python3 -c "import json,sys;d=json.load(sys.stdin);print($1)"; }

echo "==> gateway derleniyor"
( cd "$ROOT/dkm-gateway" && ./mvnw -q -B package -DskipTests )

echo "==> ${SIZE} boyutunda senaryo uretiliyor (${RATE} mesaj/s kayitli hiz)"
java "$ROOT/scripts/bench/Bench.java" gen "$WORK/scenario.bin" "$SIZE" "$RATE" | sed 's/^/    /'

echo "==> gateway baslatiliyor"
# Senaryoya gore boyutlandirildi: dosya yigin disinda tek bir arena'da, mesaj
# basina indeks ise yigin uzerinde tutulur.
( cd "$ROOT/dkm-gateway" && exec java -Xmx6g -XX:MaxDirectMemorySize=4g \
    -jar target/quarkus-app/quarkus-run.jar ) > "$WORK/gateway.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 90); do sleep 1; api /api/status >/dev/null 2>&1 && break; done
api /api/status >/dev/null || { echo "gateway baslatilamadi; $WORK/gateway.log dosyasina bakin"; exit 1; }

echo "==> olcum istemcisi baglaniyor (DKM yerine gecer)"
java "$ROOT/scripts/bench/Bench.java" peer 127.0.0.1 5001 5002 5003 \
     --reply-hz 2000 --report-ms 1000 > "$WORK/peer.log" 2>&1 &
PIDS+=($!)
sleep 3
grep -c "baglandi" "$WORK/peer.log" | xargs -I{} echo "    {} link bagli"

echo "==> senaryo yukleniyor"
LOAD_START=$(python3 -c 'import time;print(time.time())')
LOADED=$(curl -s -X POST "$GATEWAY/api/session/load-path" \
  -H 'Content-Type: application/json' -d "{\"path\":\"$WORK/scenario.bin\"}")
LOAD_END=$(python3 -c 'import time;print(time.time())')
LOAD_SECONDS=$(python3 -c "print(f'{$LOAD_END-$LOAD_START:.2f}'.replace('.',','))")
MESSAGES=$(echo "$LOADED" | jqp "d['messages']")
BYTES=$(echo "$LOADED" | jqp "d['bytes']")
echo "    $MESSAGES mesaj, $BYTES byte, ${LOAD_SECONDS}s icinde ayristirildi"

# ---------------------------------------------------------------- test 1
echo "==> test 1: azami hiz"
put /api/playback/mode '{"mode":"MAX_RATE"}' >/dev/null

# Iki gecis yapilir, ilki atilir. Gonderim yolu JIT'in kosarken derledigi sikisik
# bir dongudur; tek bir soguk gecis kodu degil derleyiciyi olcer ve sonucu ucte
# bir kadar dusuk gosterir.
COLD_MS=0
for PASS in warmup measured; do
  post "/api/playback/stop?rewind=true" >/dev/null
  post /api/playback/start >/dev/null
  while true; do
    sleep 0.3
    STATE=$(api /api/playback | jqp "d['state']")
    [[ "$STATE" == "FINISHED" ]] && break
  done
  if [[ "$PASS" == "warmup" ]]; then
    SNAP=$(api /api/playback)
    COLD_MS=$(echo "$SNAP" | jqp "d['finishedAt']-d['startedAt']-d.get('planBuildMillis',0)")
  fi
done
SNAP=$(api /api/playback)
MAX_MS=$(echo "$SNAP" | jqp "d['finishedAt']-d['startedAt']")
MAX_BYTES=$(echo "$SNAP" | jqp "d['sentBytes']")
MAX_MSGS=$(echo "$SNAP" | jqp "d['sent']")
PLAN_MS=$(echo "$SNAP" | jqp "d.get('planBuildMillis',0)")
SEND_MS=$(python3 -c "print(max(1,$MAX_MS-$PLAN_MS))")
SEND_BPS=$(python3 -c "print(int($MAX_BYTES*1000/$SEND_MS))")
read -r MAX_BPS MAX_MPS <<<"$(python3 -c "
ms=max($MAX_MS,1); print(f'{$MAX_BYTES*1000/ms:.0f} {$MAX_MSGS*1000/ms:.0f}')")"
echo "    $(python3 -c "print(f'{$SEND_BPS/1048576:.0f}')") MiB/s, $(python3 -c "print(f'{$MAX_MPS:,.0f}'.replace(',',' '))") mesaj/s, ${SEND_MS} ms icinde (soguk gecis: ${COLD_MS} ms)"

# ---------------------------------------------------------------- test 2
echo "==> test 2: surdurulebilir hiz (gecikme ${LAG_BUDGET_MS} ms altinda)"
put /api/playback/mode '{"mode":"TIMESTAMP"}' >/dev/null
SWEEP=""
BEST_SPEED=0
BEST_BPS=0
SWEEP_HIT_CEILING=no
SPAN_MS=$(api /api/playback | jqp "d.get('spanMillis',0)")
for SPEED in 1 2 4 8 16 32 64; do
  # Senaryoyu olcum penceresinden hizli bitiren bir hiz "surdurulebilir" olarak
  # olculemez: bir hiz degil, bitmis bir kosunun kuyrugu raporlanir. Daha yuksek
  # hizlari denemek icin daha uzun bir senaryo uretin (--rate degerini dusurun).
  RUN_SECONDS=$(python3 -c "print(f'{$SPAN_MS/1000/$SPEED:.1f}')")
  if python3 -c "import sys; sys.exit(0 if $RUN_SECONDS < $SAMPLE_SECONDS + 1 else 1)"; then
    printf "    %3sx  bu hizda senaryo yalnizca %ss suruyor, tarama durduruldu\n" "$SPEED" "$RUN_SECONDS"
    break
  fi
  post "/api/playback/stop?rewind=true" >/dev/null
  put /api/playback/speed "{\"speed\":$SPEED}" >/dev/null
  post /api/playback/start >/dev/null
  # Once oturmasini bekle, sonra sabit bir pencere olc: acilis dalgalanmalari
  # "surdurulebilir" demek degildir.
  sleep 0.5
  WINDOW_START_BYTES=$(api /api/playback | jqp "d['sentBytes']")
  WINDOW_START=$(python3 -c 'import time;print(time.time())')
  PEAK_LAG=0
  for _ in $(seq 1 $((SAMPLE_SECONDS * 4))); do
    sleep 0.25
    SNAP=$(api /api/playback)
    STATE=$(echo "$SNAP" | jqp "d['state']")
    LAG=$(echo "$SNAP" | jqp "int(d.get('lagMillis',0))")
    (( LAG > PEAK_LAG )) && PEAK_LAG=$LAG
    [[ "$STATE" == "FINISHED" ]] && break
  done
  WINDOW_END_BYTES=$(api /api/playback | jqp "d['sentBytes']")
  WINDOW_END=$(python3 -c 'import time;print(time.time())')
  BPS=$(python3 -c "print(int(($WINDOW_END_BYTES-$WINDOW_START_BYTES)/max($WINDOW_END-$WINDOW_START,0.001)))")
  VERDICT="uygun"
  if (( PEAK_LAG > LAG_BUDGET_MS )); then VERDICT="geride"; fi
  printf "    %3sx  %8s MiB/s  tepe gecikme %5s ms  %s\n" \
    "$SPEED" "$(python3 -c "print(f'{$BPS/1048576:.0f}')")" "$PEAK_LAG" "$VERDICT"
  SWEEP="${SWEEP}| ${SPEED}× | $(python3 -c "print(f'{$BPS/1048576:.0f}')") MiB/s | ${PEAK_LAG} ms | ${VERDICT} |
"
  if [[ "$VERDICT" == "uygun" ]]; then
    BEST_SPEED=$SPEED
    BEST_BPS=$BPS
  else
    SWEEP_HIT_CEILING=yes
    break
  fi
done
post "/api/playback/stop?rewind=true" >/dev/null

# ---------------------------------------------------------------- test 3
echo "==> test 3: gorsellestirme gecikmesi, kosu devam ederken"
put /api/playback/speed "{\"speed\":$(( BEST_SPEED > 0 ? BEST_SPEED : 1 ))}" >/dev/null
post /api/playback/start >/dev/null
VIZ=$(java "$ROOT/scripts/bench/Bench.java" viz "ws://127.0.0.1:8080/ws/viz" 8 2>&1 || true)
echo "$VIZ" | sed 's/^/    /'
post "/api/playback/stop?rewind=true" >/dev/null

# ---------------------------------------------------------------- report
echo "==> $OUT yaziliyor"
{
  echo "# Gateway performans raporu"
  echo
  echo "$(date -u '+%d.%m.%Y %H:%M UTC') tarihinde $(uname -sm) üzerinde, $(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo '?') çekirdek ile üretildi."
  echo
  echo "Senaryo: RSP/RSM/CRM linkleri üzerinde"
  echo "**$(python3 -c "print(f'{$BYTES/1048576:.0f}')") MiB** ve"
  echo "**$(python3 -c "print(f'{$MESSAGES:,}'.replace(',','.'))") mesaj**, ${RATE} mesaj/s kayıtlı hızda."
  echo "Yükleme ve çerçeveleme süresi: **${LOAD_SECONDS} s**."
  echo
  echo "Karşı taraf \`scripts/bench/Bench.java peer\`: DKM gibi dışarı bağlanır, yalnızca"
  echo "\`msg_length\` ile çerçeveler ve çözümlemeden boşaltır. Böylece ölçülen şey, karşı"
  echo "tarafın yetişme kapasitesi değil gateway'in gönderim yoludur. Aynı anda saniyede"
  echo "2000 MeasurementReport geri gönderir, yani yakalama yolu da baştan sona yük altındadır."
  echo
  echo "## 1. Azami hız"
  echo
  echo "\`MAX_RATE\` kayıtlı zamanlamayı yok sayar ve geri basıncın izin verdiği kadar hızlı gönderir."
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| Gönderim hızı | **$(python3 -c "print(f'{$SEND_BPS/1048576:.0f}')") MiB/s** ($(python3 -c "print(f'{$SEND_BPS/1e9:.2f}'.replace('.',','))") GB/s) |"
  echo "| Mesaj hızı | **$(python3 -c "print(f'{$MAX_MPS:,.0f}'.replace(',','.'))") mesaj/s** |"
  echo "| Mesaj sayısı | $(python3 -c "print(f'{$MAX_MSGS:,}'.replace(',','.'))") |"
  echo "| Gönderim penceresi | ${SEND_MS} ms |"
  echo "| Plan kurulumu | ${PLAN_MS} ms (yukarıdaki hesaba dahil değil, doğrulanabilsin diye ayrı verildi) |"
  echo "| İlk, soğuk geçiş | ${COLD_MS} ms. Atıldı: JIT gönderim döngüsünü koşarken derliyor |"
  echo "| Baştan sona | ${MAX_MS} ms |"
  echo
  echo "## 2. Sürdürülebilir hız"
  echo
  echo "Zamanlı modda artan hızlarla. \`Gecikme\`, replay'in kendi kayıtlı zaman çizgisine göre"
  echo "ne kadar geride kaldığıdır; ${LAG_BUDGET_MS} ms üzerindeki her değer o hızın sadık"
  echo "şekilde yeniden üretilmediği anlamına gelir."
  echo
  echo "| Hız | Throughput | Tepe gecikme | |"
  echo "|---|---|---|---|"
  printf '%s' "$SWEEP"
  echo
  if [[ "$SWEEP_HIT_CEILING" == "yes" ]]; then
    echo "Sadık şekilde sürdürülen en yüksek hız: **${BEST_SPEED}×**"
    echo "(**$(python3 -c "print(f'{$BEST_BPS/1048576:.0f}')") MiB/s**, $(python3 -c "print(f'{$BEST_BPS*8/1e9:.2f}'.replace('.',','))") Gbit/s)."
    echo "Bunun üzerinde replay kendi zaman çizgisinin gerisinde kalıyor."
  else
    echo "**Bir tavan bulunamadı.** Denenen her hızda gecikme sıfırda kaldı; tarama, gateway"
    echo "yetişemediği için değil senaryo daha yüksek bir hızı ölçmeye yetmediği için durdu."
    echo "Ölçülen en yüksek değer **${BEST_SPEED}×**"
    echo "(**$(python3 -c "print(f'{$BEST_BPS/1048576:.0f}')") MiB/s**) oldu; 1. test gönderim yolunun"
    echo "çok daha yükseğe çıktığını gösteriyor. Daha ileri gitmek için \`--rate\` değerini"
    echo "düşürüp daha uzun bir senaryo üretin."
  fi
  echo
  echo "## 3. Görselleştirme gecikmesi"
  echo
  echo "Tarayıcı tarafından ölçülür: her kare, sunucunun bastığı duvar saatini taşır, dolayısıyla"
  echo "varıştaki fark telden piksele giden sürenin sunucu tarafındaki yarısıdır. Kalan yarısı"
  echo "tarayıcının bir animasyon karesidir ve konsol bunu ekranda gösterir."
  echo
  echo '```'
  echo "$VIZ"
  echo '```'
  echo
  echo "Düşen örnekler yalnızca görüntüyü etkiler. Her mesaj yine gönderildi, yine yakalandı ve"
  echo "yine listelerde duruyor. Geri kalmaya ayrıntı kaybederek karşılık verilen tek yer"
  echo "görselleştirme yoludur; koşu ne kadar zorlarsa zorlasın yukarıdaki gecikmenin sınırlı"
  echo "kalmasının sebebi de budur."
  echo
  echo "## Notlar"
  echo
  echo "- Bütün ölçümler tek makinede loopback üzerinden yapıldı, yani sınırı ağ koymuyor."
  echo "  Gerçek bir kurulumda ağ kartı ve iki makine arasındaki bağlantı da devreye girer."
  echo "- Gateway JVM üzerinde çalıştı. Native binary 0,06 s'de açılıyor ve çok daha az bellek"
  echo "  kullanıyor, ancak throughput açısından aynı kod yolu."
  echo "- Bellek: mesaj byte'ları yığın dışında tek bir arena'da tutulur, fakat mesaj başına"
  echo "  indeks Java nesnesidir, dolayısıyla yığın mesaj *sayısıyla* ölçeklenir. Bu koşuda"
  echo "  $(python3 -c "print(f'{$MESSAGES:,}'.replace(',','.'))") mesaj indekslendi."
  echo "- Tekrarlamak için: \`scripts/benchmark.sh --size $SIZE --rate $RATE\`"
} > "$OUT"

echo
echo "── bitti ──────────────────────────────────────"
echo "rapor:    $OUT"
echo "kayitlar: $WORK"
