# Gateway performans raporu

28.08.2026 22:47 UTC tarihinde Darwin arm64 üzerinde, 10 çekirdek ile üretildi.

Senaryo: RSP/RSM/CRM linkleri üzerinde
**1024 MiB** ve
**6.301.300 mesaj**, 20000 mesaj/s kayıtlı hızda.
Yükleme ve çerçeveleme süresi: **0,67 s**.

Karşı taraf `scripts/bench/Bench.java peer`: DKM gibi dışarı bağlanır, yalnızca
`msg_length` ile çerçeveler ve çözümlemeden boşaltır. Böylece ölçülen şey, karşı
tarafın yetişme kapasitesi değil gateway'in gönderim yoludur. Aynı anda saniyede
2000 MeasurementReport geri gönderir, yani yakalama yolu da baştan sona yük altındadır.

## 1. Azami hız

`MAX_RATE` kayıtlı zamanlamayı yok sayar ve geri basıncın izin verdiği kadar hızlı gönderir.

| | |
|---|---|
| Gönderim hızı | **1822 MiB/s** (1,91 GB/s) |
| Mesaj hızı | **8.424.198 mesaj/s** |
| Mesaj sayısı | 6.301.300 |
| Gönderim penceresi | 562 ms |
| Plan kurulumu | 186 ms (yukarıdaki hesaba dahil değil, doğrulanabilsin diye ayrı verildi) |
| İlk, soğuk geçiş | 780 ms. Atıldı: JIT gönderim döngüsünü koşarken derliyor |
| Baştan sona | 748 ms |

## 2. Sürdürülebilir hız

Zamanlı modda artan hızlarla. `Gecikme`, replay'in kendi kayıtlı zaman çizgisine göre
ne kadar geride kaldığıdır; 50 ms üzerindeki her değer o hızın sadık
şekilde yeniden üretilmediği anlamına gelir.

| Hız | Throughput | Tepe gecikme | |
|---|---|---|---|
| 1× | 3 MiB/s | 0 ms | uygun |
| 2× | 7 MiB/s | 0 ms | uygun |
| 4× | 13 MiB/s | 0 ms | uygun |
| 8× | 26 MiB/s | 0 ms | uygun |
| 16× | 52 MiB/s | 0 ms | uygun |
| 32× | 104 MiB/s | 0 ms | uygun |

**Bir tavan bulunamadı.** Denenen her hızda gecikme sıfırda kaldı; tarama, gateway
yetişemediği için değil senaryo daha yüksek bir hızı ölçmeye yetmediği için durdu.
Ölçülen en yüksek değer **32×**
(**104 MiB/s**) oldu; 1. test gönderim yolunun
çok daha yükseğe çıktığını gösteriyor. Daha ileri gitmek için `--rate` değerini
düşürüp daha uzun bir senaryo üretin.

## 3. Görselleştirme gecikmesi

Tarayıcı tarafından ölçülür: her kare, sunucunun bastığı duvar saatini taşır, dolayısıyla
varıştaki fark telden piksele giden sürenin sunucu tarafındaki yarısıdır. Kalan yarısı
tarayıcının bir animasyon karesidir ve konsol bunu ekranda gösterir.

```
ws://127.0.0.1:8080/ws/viz olculuyor, 8 s...

── gorsellestirme akisi ──────────────────────
  kare     500 / 8 s (62.5/s)
  ornek    2,048,000 (256,000/s)
  dusen    569,973 (yukarida)
  sunucudan tarayiciya       p50 1.0 ms   p95 1.0 ms   p99 1.0 ms   azami 13.0 ms
```

Düşen örnekler yalnızca görüntüyü etkiler. Her mesaj yine gönderildi, yine yakalandı ve
yine listelerde duruyor. Geri kalmaya ayrıntı kaybederek karşılık verilen tek yer
görselleştirme yoludur; koşu ne kadar zorlarsa zorlasın yukarıdaki gecikmenin sınırlı
kalmasının sebebi de budur.

## Notlar

- Bütün ölçümler tek makinede loopback üzerinden yapıldı, yani sınırı ağ koymuyor.
  Gerçek bir kurulumda ağ kartı ve iki makine arasındaki bağlantı da devreye girer.
- Gateway JVM üzerinde çalıştı. Native binary 0,06 s'de açılıyor ve çok daha az bellek
  kullanıyor, ancak throughput açısından aynı kod yolu.
- Bellek: mesaj byte'ları yığın dışında tek bir arena'da tutulur, fakat mesaj başına
  indeks Java nesnesidir, dolayısıyla yığın mesaj *sayısıyla* ölçeklenir. Bu koşuda
  6.301.300 mesaj indekslendi.
- Tekrarlamak için: `scripts/benchmark.sh --size 1G --rate 20000`
