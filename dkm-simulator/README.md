# dkm-simulator

**Bu dizin teslim edilen iş değil.** Proje başlamadan önce var olan C++ test iskelesi.
Burada iki işi görüyor: gerçek DKM'nin yerine geçmek, ve gateway'in doğruluğunu kendisine
karşı sınayabileceğimiz bir referans olmak.

Karşılanması istenen isterlerin tamamı [`req.md`](req.md) içinde.

## Üç program

| Program | Ne yapar | Projede rolü |
|---|---|---|
| [`mock_r/`](mock_r) | DKM'nin kendisi. Üç link üzerinden bağlanır, gelen mesajları işler, `MeasurementReport` üretir. | Gateway'in karşısındaki taraf. Demo ve testler buna karşı koşar. |
| [`bin_gen/`](bin_gen) | Uyarım ikilisi üretir. | `samples/` altındaki senaryolar bununla üretildi. |
| [`c_sim/`](c_sim) | Başsız replay istemcisi: üç portu dinler, bir dosyayı `mock_r`'a oynatır, dönenleri basar. | Gateway'in yaptığı işin referans uygulaması. Gateway'in davranışını buna karşı doğrulayabilirsiniz. |

`c_sim` gateway'in C++ atası sayılabilir. Aynı problemi çözer — tek bir paylaşılan replay
saatinden üç linke birden oynatmak — ve gateway'in neden öyle kurulduğunu anlamak isteyen
biri için en kısa okuma o dosyadır.

## Bilinmesi gereken dört gerçek

Gateway'in tasarımı bunların üzerine oturuyor; hepsi bu dizindeki koddan okunabilir.

**Tel formatı.** Sabit 40 baytlık `MsgHeader` (beş `std::size_t`), hemen ardından gövde.
Yerel bayt sırası, ayrı bir serileştirme adımı yok. `msg_length` başlık dahil toplam boyuttur
ve çerçeveleme için tek yetkili alandır — tip başına boyut tablosuna gerek yok. İkili dosya
biçimi ile tel biçimi aynıdır.

**Üç ayrı TCP linki.** RSP (5001), RSM (5002), CRM (5003). Her modül için bir tane, her biri
bağımsız olarak çift yönlü. Gerçek hedefte bunlar ayrı PCIe hatlarına karşılık geliyor.

**DKM istemci taraftır.** `MessageChannel::connect()` her link için kendi açılışında **bir
kez** dener ve başarısız olursa vazgeçer. Karşı taraf (bizim gateway, ya da `c_sim`) önceden
dinlemede olmak zorundadır. Demo sırasında bir şey çalışmıyorsa sebebi neredeyse her zaman
budur.

**Linkler arası bağımlılık gerçektir.** Bir RSP `DetectionReport`, daha önce RSM üzerinden
bir `BeamReport` ile duyurulmuş olması gereken bir `beam_id` taşır. `mock_r` duyurulmamış bir
hüzmeye ait tespiti sessizce düşürür. Üç linki ayrı ayrı zamanlamak, hızlı olanın öne
geçmesine ve bu bağımlılığın bozulmasına yol açar.

## Derleme

Üçü de bağımsız CMake projeleri. Yalnızca standart kütüphaneye ve POSIX/Winsock soketlerine
ihtiyaç duyarlar.

```bash
cmake -S mock_r  -B mock_r/build  && cmake --build mock_r/build
cmake -S bin_gen -B bin_gen/build && cmake --build bin_gen/build
cmake -S c_sim   -B c_sim/build   && cmake --build c_sim/build
```

## bin_gen: senaryo üreteci

İki kipi var.

**Varsayılan kip** dönen antenli bir radar senaryosu üretir. Her hüzme önce RSM üzerinden
duyurulur, hemen ardından o hüzmenin aydınlattığı her şey RSP üzerinden bir
`DetectionReport` olarak gelir: ölçüm gürültüsü binmiş gerçek hedefler, artı hiçbir izin
sahiplenmeyeceği karmaşa. Hedefler hareket eder, bir kısmı döner, ve anten üzerlerinden her
geçtiğinde CRM üzerinden `track_id` taşıyan bir `Prediction` yayımlanır.

```bash
bin_gen/build/bin_gen cikti.bin --seconds 180 --targets 24 --seed 20260829
```

| Anahtar | Varsayılan | Ne yapar |
|---|---|---|
| `--seconds` | 120 | senaryo süresi |
| `--targets` | 12 | aynı anda havada olan hedef sayısı |
| `--beams` | 90 | tur başına hüzme |
| `--revolution` | 2000 | tur süresi, ms |
| `--range` | 2000 | azami menzil, metre |
| `--clutter` | 0,35 | hüzme başına ortalama karmaşa dönüşü |
| `--seed` | 20260829 | tohum; aynı tohum bayt bayt aynı dosyayı verir |
| `--legacy` | — | aşağıdaki eski on mesajlık diziyi üretir |
| `--quiet` | — | özet basma |

Üretimde gerçekçi olan iki ayrıntı var. Tespit olasılığı menzille düşer, yani uzaktaki bir
hedef her taramada görülmez — izi düz bir çizgi yerine ize benzeten şey budur. Ve kapsama
alanından çıkan bir hedef kenardan geri girerken **yeni bir iz numarası** alır; numarayı geri
kullanan bir izleyici, ekranda eski izin son konumunu yenisinin ilkine bağlayan, hiçbir
hedefin uçmadığı bir kiriş çizdirirdi.

Dosya global zaman damgası sırasında yazılır ve bir dwell'in mesajları dwell süresine oranlı
olarak yayılır: hüzme %10'da, tespit %45'te, karıştırıcı %55'te, kestirimler %65'ten sonra.
Bu kozmetik değil — hüzme ile tespit arasındaki boşluk, hızlandırılmış oynatmada linkler arası
bağımlılığın ayakta kalmasını sağlar.

## Yerindeki iki dosyaya dokunmayın

[`input.bin`](input.bin) (1048 bayt, on mesaj) ve [`output.bin`](output.bin) (128 bayt), bu
projenin doğruluk kaynağı. Gateway'in `GroundTruthDecodeTest` testi tam olarak o on mesajın
değerlerini ve `mock_r`'ın onlara verdiği yanıtı doğruluyor. Yeniden üretilirlerse test
anlamını kaybeder.

`input.bin` ayrıca kasıtlı bir eskimişlik taşıyor: içindeki `Prediction` mesajı 88 bayt, yani
`track_id` alanı eklenmeden önce yazılmış. Gateway bunu sessizce yanlış çözmek yerine
**raporlar**; NFR-5'in istediği davranış tam olarak bu ve test bunu da doğruluyor.

Aynı diziyi başka bir yere üretmeniz gerekirse:

```bash
bin_gen/build/bin_gen /tmp/legacy.bin --legacy
```

Tek fark `Prediction`'ın artık `track_id` taşıması, yani son mesaj 8 bayt daha uzun.

## Bu iskeleyi tek başına çalıştırmak

Gateway olmadan, yalnızca C++ tarafını görmek isterseniz — `c_sim` gateway'in yerine geçer.
**Sıra bağlayıcıdır**: önce `c_sim` dinlemeye başlamalı.

```bash
bin_gen/build/bin_gen /tmp/mesajlar.bin --seconds 60
c_sim/build/c_sim /tmp/mesajlar.bin 2.0     # 2× hızda oynatır, dönenleri basar
mock_r/build/mock_r                          # ayrı bir terminalde
```

Windows tarafında aynı akışı [`run_demo.bat`](run_demo.bat) kurar: üç programı derler,
bir senaryo üretir, `c_sim`'i dinlemeye alır ve ardından `mock_r`'ı başlatır.

`mock_r` çalıştığı dizine iki dosya yazar: aldığı her şeyi `input.bin`, gönderdiği her şeyi
`output.bin` olarak. `samples/` altındaki çıktı dosyaları böyle yakalandı.

## mock_r ne hesaplıyor

Gateway'in görselleştirmesi bu kuralları birebir yeniden üretmek zorunda (FR-26), o yüzden
burada da yazılı. Kaynak: [`mock_r/src/core/processing.cpp`](mock_r/src/core/processing.cpp).

Bir `DetectionReport` geldiğinde:

1. `beam_id` daha önce duyurulmuş mu diye bakılır. Değilse mesaj düşürülür.
2. Hüzmenin `beam_type` alanı 1 ise, tespitlerin ortalamasına **en yakın** olanı seçilir;
   değilse hepsinin ortalaması alınır.
3. Sonuç, tanımlı kapı alanlarından herhangi birinin **içindeyse** bastırılır.
4. Raporlama alanı tanımlıysa, sonuç kutupsaldan kartezyene çevrilip
   (`x = mesafe·cos(açı)`, `y = mesafe·sin(açı)`, açı radyan) en az birinin içinde olmalıdır.
5. Kalanlar RSM üzerinden `MeasurementReport` olarak geri gönderilir.

Kapı alanı kutupsal, raporlama alanı kartezyendir. Gateway bu ayrımı koruyor: biri halka
dilimi, diğeri dikdörtgen olarak çiziliyor. Farklı bir kural kullanmak, operatöre DKM'nin
uyguladığından başka bir içeride/dışarıda kararı göstermek olurdu.

Durum tamponları sınırlıdır ve bu ölçümde görünür hale geliyor: hüzme tamponu 100 slotluk
(`beam_id % 100`), alan tamponları 16'şar. Ayrıca `mock_r` üç linki üç bağımsız iş parçacığıyla
dinliyor ve bu tamponları işleme iş parçacıklarıyla senkronize etmiyor — yüksek hızlarda
arada sırada bir tespit, hüzmesinden önce işlenebiliyor. `samples/README.md` bunun ölçülen
büyüklüğünü veriyor.
