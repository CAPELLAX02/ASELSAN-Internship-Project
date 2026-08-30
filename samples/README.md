# Örnek uyarım ve yakalama dosyaları

Konsolu ve gateway'i gerçek bir radar resmiyle çalıştırmak için hazır ikili dosyalar.
Hepsi `dkm-simulator/bin_gen` ile üretildi; tohumları sabit, dolayısıyla bayt bayt
yeniden üretilebilirler.

| Dosya | Boyut | İçerik |
|---|---|---|
| `radar-demo-180s.in.bin` | 1,35 MB · 13.648 mesaj | 3 dakikalık tarama. Gösterim için olan. |
| `radar-demo-180s.out.bin` | 196 KB · 3.128 mesaj | `mock_r`'ın bu girdiye verdiği yanıt. |
| `radar-soak-600s.in.bin` | 7,74 MB · 73.384 mesaj | 10 dakikalık tarama, iki katı hedef ve karmaşa. Uzun koşu için. |
| `radar-soak-600s.out.bin` | 1,23 MB · 20.090 mesaj | `mock_r`'ın uzun koşuya verdiği yanıt. |

`.in.bin` gateway'e yüklenen uyarımdır, `.out.bin` ise DKM'nin geri gönderdiğidir.
İki dosyanın biçimi aynı: art arda dizilmiş ham mesajlar, her biri 40 baytlık bir
`MsgHeader` ve ardından gövdesi. Sınırlar `msg_length` alanından gelir, ayraç yoktur.
Bu, `mock_r`'ın kendi kaydedicisinin ürettiği biçimin aynısıdır.

## Senaryoda ne var

Anten azimutta dönüyor. Her hüzme önce RSM üzerinden duyuruluyor, hemen ardından o
hüzmenin aydınlattığı her şey RSP üzerinden bir `DetectionReport` olarak geliyor:
ölçüm gürültüsü binmiş gerçek hedefler, artı hiçbir izin sahiplenmeyeceği karmaşa.
Hedefler hareket ediyor, bir kısmı dönüyor, ve anten üzerlerinden her geçtiğinde CRM
üzerinden `track_id` taşıyan bir `Prediction` yayımlanıyor. Kapı ve raporlama alanları
başta ve her 20 turda bir yeniden duyuruluyor, bir karıştırıcı sabit bir kerterizde
duruyor.

Gerçekçi olan tarafı, tespit olasılığının menzille düşmesi: uzaktaki bir hedef her
taramada görülmüyor. İzi düz bir çizgi yerine ize benzeten şey bu. Kapsama alanından
çıkan bir hedef kenardan geri giriyor ama **yeni bir iz numarasıyla**; numarayı geri
kullanan bir izleyici, ekranda eski izin son konumunu yenisinin ilkine bağlayan,
hiçbir hedefin uçmadığı bir kiriş çizdirirdi.

Üç bağlantının üçü de dolu, yedi mesaj tipinin yedisi de var:

```
radar-demo-180s.in.bin
  RSM 8.205   BeamReport 8.100 · ReadCommand 90 · GateAreaMsg 10 · ReportingAreaMsg 5
  RSP 3.288   DetectionReport 3.198 · JammerReport 90
  CRM 2.155   Prediction 2.155   (aynı anda 24 hedef, toplam 136 farklı iz)
```

## Dosyalar global zaman damgası sırasında

Bir dwell'in mesajları dwell süresine oranlı olarak yayılıyor: hüzme %10'da, tespit
%45'te, karıştırıcı %55'te, kestirimler %65'ten sonra. Bu kozmetik değil. DKM, hüzmesini
henüz duymadığı bir tespiti sessizce düşürür ve bu ikisi ona ayrı bağlantılardan, ayrı
iş parçacıklarına ulaşır. Aradaki boşluk (demoda 8 ms, uzun koşuda 6 ms) hızlandırılmış
oynatmada bile bu bağımlılığın ayakta kalmasını sağlar.

## Çıktı dosyaları nasıl doğrulandı

`.out.bin` dosyaları elle yazılmadı: gateway ayakta ve `mock_r` bağlıyken ilgili `.in.bin`
1× hızda oynatıldı, `mock_r` da döndürdüklerini kendi çalışma dizinine `output.bin` olarak
yazdı.

Sonra her ikili, DKM'nin `processing.cpp` içindeki kurallarının bağımsız bir uygulamasına
karşı denetlendi (hüzme araması, ortalama ya da ortalamaya en yakın seçimi, kapı ve
raporlama alanı testleri):

```
radar-demo-180s   beklenen 3.128 · gelen 3.128 · eksik 0 · fazla 0   birebir
                  düşenler: 69 kapı alanı, 1 raporlama alanı
radar-soak-600s   beklenen 20.099 · gelen 20.090 · eksik 12 · fazla 3
                  düşenler: 426 kapı alanı, 5 raporlama alanı
```

Demo ikilisi tam olarak tutuyor. Uzun koşudaki 15 mesajlık fark (%0,07) `mock_r`'ın kendi
iş parçacığı düzeninden geliyor: üç bağlantıyı üç bağımsız alıcı iş parçacığıyla dinliyor
ve durum tamponlarını (hüzme, kapı alanı) işleme iş parçacıklarıyla senkronize etmiyor.
Saniyede 60 hüzmede, arada sırada bir tespit hüzmesinden önce, bir de tespit kapı alanı
kurulmadan önce işleniyor. Gateway her iki koşuda da planladığı mesajın tamamını sıfır
gecikmeyle gönderdi; fark alıcı tarafta.

## Nasıl yeniden üretilir

```bash
cmake -S dkm-simulator/bin_gen -B dkm-simulator/bin_gen/build && cmake --build dkm-simulator/bin_gen/build
```

```bash
dkm-simulator/bin_gen/build/bin_gen samples/radar-demo-180s.in.bin --seconds 180 --targets 24 --seed 20260829
```

```bash
dkm-simulator/bin_gen/build/bin_gen samples/radar-soak-600s.in.bin --seconds 600 --targets 40 --revolution 1500 --clutter 0.6 --seed 7391
```

`bin_gen --help` bütün anahtarları listeler: senaryo süresi, hedef sayısı, tur başına
hüzme, tur süresi, azami menzil, karmaşa yoğunluğu, tohum.

## Eski on mesajlık dizi

`dkm-simulator/input.bin` ve `output.bin` yerinde duruyor, dokunulmamalı: gateway'in
`GroundTruthDecodeTest`'i tam olarak o on mesajın değerlerini doğruluyor. Aynı diziyi
üretmek gerekirse:

```bash
dkm-simulator/bin_gen/build/bin_gen /tmp/legacy.bin --legacy
```

Tek fark, `Prediction` yapısının o dosya alındığından beri `track_id` alanı kazanmış
olması: son mesaj 8 bayt daha uzun.

## Manuel mi, otomatik mi

Yükleme ve **Başlat** operatörün elinde kalır; ister dokümanı böyle istiyor (FR-6, FR-11).
Hangi uyarımın canlı bir bağlantıya gideceği ve ne zaman gideceği bir karardır, bir
servisin açılmasının yan etkisi değil.

Tekrarlanabilir bir gösterim ya da CI koşusu içinse gateway açılışta bir dosyayı
yükleyebilir; **Başlat** yine sizde kalır:

```bash
DKM_SESSION_PRELOAD=samples/radar-demo-180s.in.bin ./mvnw quarkus:dev
```

Varsayılan olarak kapalıdır.
