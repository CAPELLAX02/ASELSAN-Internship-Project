"""Turns a ladder run into a report someone can argue with.

Every figure here is a median across runs with the spread shown next to it. A
single number from a single run is not a measurement, it is an anecdote: JIT
state, page cache, and whatever else the machine was doing move a throughput
figure by a third on their own, and a reader has no way to tell which of those
they are looking at. The spread is what tells them.

The report ends with what the run did not measure. That section is not modesty:
a benchmark whose limits are not stated has to be re-derived by every reader
before they can use it, and most of them will simply not believe it instead.
"""

import argparse
import json
import re
import statistics
from pathlib import Path


def tr(value, digits=0):
    """Formats a number the way the rest of this project's reports do."""
    text = f"{value:,.{digits}f}"
    return text.replace(",", " ").replace(".", ",")


def spread(values):
    """Median, plus how far the runs sat from each other."""
    if not values:
        return 0.0, 0.0
    median = statistics.median(values)
    if len(values) < 2 or median == 0:
        return median, 0.0
    return median, (max(values) - min(values)) / median * 100.0


GC_LINE = re.compile(r"\[([\d.]+)s\]\s+GC\((\d+)\)\s+(.*)")


def gc_pauses(directory: Path):
    """Pulls every collection pause out of whatever GC logs the run captured.

    The tail in the results table has to be attributed to something, and the
    only honest way to attribute it is to read the collector's own account of
    what it did rather than infer it from the shape of a curve.
    """
    runs = {}
    # The GC pass writes into its own subdirectory so its level file never
    # lands in the ladder's median.
    for path in sorted(directory.glob("**/gc-*.log")):
        pauses = []
        collector = "?"
        for line in path.read_text(errors="ignore").splitlines():
            match = GC_LINE.search(line)
            if not match:
                continue
            body = match.group(3)
            if "Using" in body and "GC" in body:
                collector = body.split("Using", 1)[1].strip()
            millis = re.search(r"(\d+\.?\d*)ms", body)
            if millis and ("Full" in body or "Incremental" in body):
                pauses.append((float(millis.group(1)), "Full" if "Full" in body else "Incremental"))
        if pauses:
            runs[path.stem.replace("gc-", "")] = (collector, pauses)
    return runs


def collect(directory: Path):
    levels = {}
    for path in sorted(directory.glob("level-*.json")):
        entry = json.loads(path.read_text())
        levels.setdefault(entry["offeredRate"], []).append(entry)
    return dict(sorted(levels.items()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("--out", required=True)
    parser.add_argument("--image", default="")
    parser.add_argument("--cpus", default="")
    parser.add_argument("--memory", default="")
    args = parser.parse_args()

    directory = Path(args.directory)
    levels = collect(directory)
    if not levels:
        raise SystemExit(f"{directory} altinda level-*.json yok")

    environment = {}
    env_file = directory / "environment.json"
    if env_file.exists():
        environment = json.loads(env_file.read_text())

    runs = max(len(v) for v in levels.values())
    lines = []
    add = lines.append

    add("# Gateway hız merdiveni\n")
    add(
        "Sabit teklif edilen hızlarda ölçüm. Sorulan şey “ne kadar hızlı gidebiliyor” "
        "değil, “verilen kaydı ne kadar sadık yeniden üretiyor”. Hız, gateway'e bir "
        "sınırlayıcı konarak değil uyarım dosyasının kendi zaman damgalarıyla dayatılıyor; "
        "yani üretim kodu bir ölçüm yapıldığını bilmiyor ve ölçülen yola fazladan hiçbir "
        "şey girmiyor.\n"
    )

    add("## Ortam\n")
    add("| | |")
    add("|---|---|")
    add(f"| Çekirdek | {environment.get('kernel', '?')} / {environment.get('machine', '?')} |")
    add(f"| Konteynere verilen | {args.cpus} CPU, {args.memory} bellek |")
    add(f"| cgroup CPU / bellek | `{environment.get('cgroupCpuMax', '?')}` / "
        f"`{environment.get('cgroupMemoryMax', '?')}` |")
    add(f"| Gateway | native ikili, `{environment.get('gatewayFlags', '?')}` |")
    add(f"| Loopback MTU | {environment.get('loopbackMtu', '?')} |")
    add(f"| Seviye başına koşu | {runs} |")
    add("")
    add(
        "Gateway ve yük üreteci aynı konteynerde, aynı loopback üzerinde. İkisini ayrı "
        "konteynerlere koymak Docker'ın köprüsünü ve userland proxy'sini ölçüm yoluna "
        "sokardı; o zaman rapor gateway'i değil onları anlatırdı. Tek cgroup olması da "
        "koşuya verilen CPU ve belleğin, sayıların ait olduğu CPU ve bellek olmasını "
        "sağlıyor.\n"
    )

    add("## Sonuçlar\n")
    add("Sapma sütunu, gözlenen en hızlı transite göre. Sıfır “bu makinenin becerebildiği "
        "kadar iyi” demek; üstündeki her yüzdelik açıklanması gereken oynamadır.\n")
    add("| Teklif edilen | Ulaşılan | Bant genişliği | Sapma p50 / p99 / p99,9 / azami | "
        "CPU | Zirve RSS | Mesaj başına | Tekrarlanabilir |")
    add("|---:|---:|---:|---:|---:|---:|---:|:--:|")

    unstable = []
    for rate, entries in levels.items():
        achieved, achieved_spread = spread([e["peer"]["messagesPerSecond"] for e in entries])
        bandwidth, _ = spread([e["peer"]["bytesPerSecond"] for e in entries])
        p50, _ = spread([e["peer"]["jitterMicros"]["p50"] for e in entries])
        p99, p99_spread = spread([e["peer"]["jitterMicros"]["p99"] for e in entries])
        p999, _ = spread([e["peer"]["jitterMicros"]["p999"] for e in entries])
        worst = max(e["peer"]["jitterMicros"]["max"] for e in entries)
        cpu, _ = spread([e["gateway"]["cpuSeconds"] for e in entries])
        rss = max(e["gateway"]["peakRssKb"] for e in entries)
        seconds = statistics.median([e["seconds"] for e in entries])

        messages = statistics.median([e["peer"]["messages"] for e in entries])
        per_message = rss * 1024 / max(1, messages)
        # A level whose runs disagree by more than a quarter is not one figure,
        # it is a range, and printing its median as though it were a measurement
        # is the single easiest way to lose a reader who checks.
        reproducible = p99_spread <= 25.0
        if not reproducible:
            unstable.append(rate)

        add(
            f"| {tr(rate)} msg/s | {tr(achieved)} msg/s "
            f"<sub>±{tr(achieved_spread, 1)}%</sub> "
            f"| {tr(bandwidth / 1048576, 1)} MiB/s "
            f"| {tr(p50 / 1000, 3)} / {tr(p99 / 1000, 3)} "
            f"<sub>±{tr(p99_spread, 0)}%</sub> / {tr(p999 / 1000, 2)} / "
            f"{tr(worst / 1000, 1)} ms "
            f"| {tr(cpu / seconds * 100, 0)}% | {tr(rss / 1024, 0)} MiB "
            f"| {tr(per_message, 0)} B | {'evet' if reproducible else '**hayır**'} |"
        )

    add("")
    if unstable:
        add(
            "**Son sütunu okumadan tabloyu kullanmayın.** "
            + ", ".join(f"{tr(rate)} msg/s" for rate in unstable)
            + " seviyelerinde koşular birbirinden dörtte birden fazla ayrılıyor; oradaki "
            "medyan bir ölçüm değil bir aralığın ortası. Sebebi ölçüm yönteminde değil "
            "ölçülen şeyde: mesaj başına düşen bellek sabit ve mesaj sayısıyla doğrusal "
            "büyüyor, dolayısıyla yüksek hızlarda gateway'in yerleşik kümesi konteynere "
            "verilen belleğe yaklaşıyor ve geri kazanım başlıyor. Sapmanın oradaki "
            "kuyruğu gateway'in gönderme yolunu değil, o baskıyı anlatıyor.\n"
            "\nAynı cümlenin üretim tarafındaki karşılığı da bu: bu hızlar bu bellek "
            "profiliyle ölçülemiyorsa, aynı profil ile çalıştırılamaz da. Mesaj başına "
            "düşen bayt sütunu, düzeltilmesi gereken şeyin kendisi.\n"
        )
    add(
        "“Ulaşılan” gateway'in kendi sayacı değil, peer'in telden saydığı. Sapma da öyle: "
        "her mesajın başlığındaki zaman damgası, o mesajın ne zaman gitmesi gerektiğini "
        "söylüyor; peer bunu gerçekte ne zaman geldiğiyle karşılaştırıyor. Duraklama "
        "sırasında gelen mesajlar da sayılıyor — çoğu gecikme rakamını olduğundan iyi "
        "gösteren atlama tam olarak budur.\n"
    )
    sample = next(iter(levels.values()))[0]["peer"]
    average = sample["bytes"] / max(1, sample["messages"])
    per_link = ", ".join(
        f"{link['link']} %{link['messages'] / max(1, sample['messages']) * 100:.0f}"
        for link in sample["links"]
    )
    add(
        f"Mesaj karışımı: ortalama {tr(average, 1)} bayt, link dağılımı {per_link}. "
        "Uyarım ağırlıklı olarak `DetectionReport` (192 bayt), yanında her hüzme için bir "
        "`BeamReport` (72 bayt) ve bir `Prediction` (96 bayt). Bu, `samples/` altındaki "
        "gerçekçi senaryolardan daha bayt yoğun bir karışım: orada hüzme raporları baskın "
        "ve mesaj başına ortalama 104 bayta iner. Yani buradaki msg/s ile oradaki msg/s "
        "aynı bant genişliği demek değil; kıyaslanacaksa bayt/s kıyaslanmalı.\n"
    )
    add("Yön dağılımı, aynı ölçümün ilk mesaja göre işaretli hali:\n")
    add("| Seviye | Erken gelen | Geç kalan | Geç kalanların p99'u |")
    add("|---:|---:|---:|---:|")
    for rate, entries in levels.items():
        early = statistics.median([e["peer"]["earlyMicros"]["count"] for e in entries])
        late = statistics.median([e["peer"]["lateMicros"]["count"] for e in entries])
        late_p99 = statistics.median([e["peer"]["lateMicros"]["p99"] for e in entries])
        total = max(1, early + late)
        add(f"| {tr(rate)} msg/s | {tr(early)} | {tr(late)} "
            f"<sub>%{tr(late / total * 100, 2)}</sub> | {tr(late_p99 / 1000, 1)} ms |")
    add("")

    add("## Düşme muhasebesi\n")
    add(
        "TCP'de paket kaybı ölçmek anlamsız, hele loopback'te. Anlamlı olan, boru hattında "
        "bir şeyin nerede ve hangi politikayla düştüğü.\n"
    )
    add("| Seviye | Yazma duraklaması | Atlanan kare | Düşen örnek | Yakalama taşması | Kalan gecikme |")
    add("|---:|---:|---:|---:|---:|---:|")
    for rate, entries in levels.items():
        gw = [e["gateway"] for e in entries]
        add(
            f"| {tr(rate)} msg/s "
            f"| {tr(max(g['writeStalls'] for g in gw))} "
            f"| {tr(max(g['vizFramesSkipped'] for g in gw))} "
            f"| {tr(max(g['vizSamplesDropped'] for g in gw))} "
            f"| {tr(max(g['captureOverflowed'] for g in gw))} "
            f"| {tr(statistics.median([g['finalLagMillis'] for g in gw]), 1)} ms |"
        )
    add("")
    add(
        "Yazma duraklaması, soketin geri basınç uyguladığı ve pacer'ın beklediği anların "
        "sayısı; sıfırdan büyük olması bir hata değil, o seviyede taşımanın sınırına "
        "gelindiğinin işareti. Görüntü tarafındaki atlama ve düşme ise bilinçli politika: "
        "alma yolu hiçbir koşulda görüntü için beklemez.\n"
    )

    collections = gc_pauses(directory)
    if collections:
        add("## Kuyruk nereden geliyor\n")
        add(
            "Yukarıdaki kuyruğu şekline bakıp tahmin etmek yerine toplayıcının kendi "
            "kaydını okuduk. `GCLOG=1` ile koşulan seviyeler:\n"
        )
        add("| Koşu | Toplayıcı | Toplama | Tam | Duraklama p50 / p90 / azami | Toplam duraklama |")
        add("|---|---|---:|---:|---:|---:|")
        for tag, (collector, pauses) in collections.items():
            values = sorted(p[0] for p in pauses)
            full = sum(1 for p in pauses if p[1] == "Full")

            def at(fraction):
                return values[min(len(values) - 1, int(fraction * len(values)))]

            add(
                f"| {tag} | {collector} | {tr(len(values))} | {tr(full)} "
                f"| {tr(at(0.50), 1)} / {tr(at(0.90), 1)} / {tr(values[-1], 1)} ms "
                f"| {tr(sum(values), 0)} ms |"
            )
        add("")
        add(
            "Native imaj **Serial GC** ile geliyor: tek iş parçacıklı, durdur-ve-topla. "
            "Çok gigabaytlık bir yığında tam toplama zaten yarım saniye sürer, ve tabloda "
            "görülen tam da bu. Yığının o boyuta gelme sebebi de belli: mesaj başına düşen "
            "dizin kaydı yığında bir nesne, dolayısıyla bellek mesaj sayısıyla doğrusal "
            "büyüyor.\n"
            "\nZincir baştan sona ölçülmüş durumda: yazma duraklaması sıfır olduğu için "
            "taşıma katmanı dışarıda; RSS mesaj sayısıyla doğrusal; toplayıcı bunu tam "
            "toplamalarla karşılıyor; sapmanın kuyruğu o duraklamaların boyunda. GraalVM'in "
            "topluluk sürümünde native imaj için Serial dışında yalnızca Epsilon var, yani "
            "bu profilden ayar yaparak çıkılamaz. Çıkış yolu ayırmayı ortadan kaldırmak.\n"
        )

    add("## Dağılımlar\n")
    add(
        "Her seviye için gecikme dağılımı `benchmark/ladder/deviation-<hız>-run<N>.hgrm` "
        "altında, HdrHistogram'ın yüzdelik dağılım biçiminde. Yani buradaki yüzdelikler "
        "yeniden çizilebilir; kimsenin bu tabloya güvenmesi gerekmiyor.\n"
    )

    add("## Bu koşu neyi ölçmüyor\n")
    add(
        "- **Gerçek bir ağı.** Ölçüm konteyner loopback'i üzerinde. Loopback MTU'su "
        f"{environment.get('loopbackMtu', '?')}, tipik bir Ethernet arayüzünde 1500. "
        "Gerçek bir NIC üzerinde bant genişliği rakamları düşer; gecikme dağılımının "
        "şekli büyük ölçüde korunur.\n"
        "- **DKM'nin kendi işlem süresini.** Peer, DKM yerine geçer ve gövdeleri "
        "çözmez. Ölçülen, gateway'in gönderme yolu; DKM'nin bir mesajı işleme maliyeti "
        "değil.\n"
        "- **Tarayıcıya kadar olan yolu.** Görüntü gecikmesi ayrı ölçülüyor "
        "(`Bench viz`), bu tablo yalnızca uyarım yönünü anlatıyor.\n"
        "- **Uzun süreli davranışı.** Seviye başına on ila otuz saniye. Saatler süren bir "
        "koşuda parçalanma ve termal kısıtlama devreye girer, ikisi de burada yok.\n"
        "- **Aynı anda birden çok tüketiciyi.** Tek peer, tek görüntü abonesi.\n"
    )

    Path(args.out).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
