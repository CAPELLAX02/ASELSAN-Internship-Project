# PC Tabanlı DKM Simülatörü

VxWorks hedefinde koşan DKM (Downloadable Kernel Module) için PC tabanlı simülatör.
DKM'yi TCP üzerinden elle düzenlenebilir uyarım mesajlarıyla besler, geri gelenleri aynı
şemayla çözer ve canlı gösterir.

Yerini aldığı döngü şuydu: ikili dosyayı elle hazırla, hedefe yükle, çıkan ikili çıktıyı
kopyala-yapıştır ile üçüncü parti bir uygulamaya taşı, orada bak. Bu simülatörle uyarımı
alan alan düzenleyip aynı koşuda tekrar gönderebiliyor, çıktıyı geldiği anda ekranda
görebiliyorsunuz.

İsterlerin tamamı [`dkm-simulator/req.md`](dkm-simulator/req.md) içinde.

---

## İçindekiler

- [Dört parça](#dört-parça)
- [Hızlı başlangıç](#hızlı-başlangıç)
- [İlk beş dakika](#i̇lk-beş-dakika)
- [Uçtan uca ne oluyor](#uçtan-uca-ne-oluyor)
- [Üzerine kurulan doğrulanmış gerçekler](#üzerine-kurulan-doğrulanmış-gerçekler)
- [Ölçüm](#ölçüm)
- [İster karşılama](#i̇ster-karşılama)
- [Depo düzeni](#depo-düzeni)
- [Geliştirme](#geliştirme)
- [Bilinen sınırlar](#bilinen-sınırlar)

---

## Dört parça

| Dizin | Nedir |
|---|---|
| [`dkm-gateway/`](dkm-gateway) | **Quarkus** servisi. Üç TCP linkini, şema/kodek motorunu, replay zamanlamasını, çıktı yakalamayı, mesaj kütüphanesini ve REST + WebSocket arayüzünü yönetir. 63 sınıf. |
| [`dkm-console/`](dkm-console) | **React / Vite / Tailwind / WebGL2** operatör konsolu. Mesaj listeleri, şemadan üretilen alan editörü, transport kontrolleri, canlı plan görünümü, açık/koyu tema, Türkçe ve İngilizce. 20 kaynak dosyası. |
| [`dkm-simulator/`](dkm-simulator) | **Teslim edilen iş değil.** Zaten var olan C++ test iskelesi: `mock_r` DKM yerine geçer, `bin_gen` uyarım üretir, `c_sim` başsız bir replay referansıdır. Burada doğrulama kaynağı olarak kullanılıyor. |
| [`samples/`](samples) | Hazır uyarım ve yakalama ikilileri. Tohumları sabit, bayt bayt yeniden üretilebilir. |
| [`scripts/`](scripts) | Demo başlatıcı, performans ölçüm takımı, hazır yapılandırılmış Prometheus + Grafana. |

Gateway ile konsol arasındaki sınır süs değil. **Gecikme veya throughput bütçesi olan her şey
gateway'de yaşar ve tarayıcıya hiç uğramaz**: çerçeveleme, zamanlama, çözümleme, yakalama,
sıralama, süzme. Konsol yalnızca bir insanın okuyabileceği hızda değişen veriyi alır — bunun
tek istisnası doğrudan tipli dizilere ve oradan GPU'ya giden ikili görselleştirme akışıdır.

## Hızlı başlangıç

> [!IMPORTANT]
> **Sıra bağlayıcıdır.** DKM istemci taraftır: kendi açılışında bir kez bağlanmayı dener ve
> bir daha denemez (§4, FR-17). Gateway üç portu dinlemeye başlamadan `mock_r` açılırsa
> bağlantı hiç kurulmaz. Pratik kural: **gateway'i yeniden başlattıysanız `mock_r`'ı da
> yeniden başlatın.**

Üç terminal, bu sırayla:

```bash
cd dkm-gateway && ./mvnw quarkus:dev
```

```bash
cd dkm-console && npm install && npm run dev
```

```bash
cd dkm-simulator/mock_r/build && ./mock_r
```

Konsolu açın (normalde <http://localhost:5173>). Üst şeritteki `RSP` `RSM` `CRM`
rozetlerinin üçü de yeşilse hazırsınız.

Hepsini tek komutla, doğru sırayla ayağa kaldırmak için:

```bash
./scripts/demo.sh
```

`mock_r` derlenmemişse:

```bash
cmake -S dkm-simulator/mock_r -B dkm-simulator/mock_r/build && cmake --build dkm-simulator/mock_r/build
```

## İlk beş dakika

**1. Uyarımı yükleyin.** **.bin aç** → [`samples/radar-demo-180s.in.bin`](samples). Sol
panelde 13 648 mesaj listelenir: üç dakikalık bir tarama — dönen anten, hareket eden
hedefler, karmaşa, izler, kapı alanları, bir karıştırıcı. Dosyayı mesajlara bölmek için tip
başına boyut tablosu kullanılmıyor; başlıktaki `msg_length` yetiyor (FR-6).

**2. Bir mesajı açın.** Listeden bir `RSP/DetectionReport` seçin. Sağda alanlar tek tek
çıkar, `detections` dizisi de sayaç alanının yönettiği canlı uzunlukta. Bu formda hiçbir
mesaj tipi yazılı değil — kontroller şemadan üretiliyor.

**3. Başlat.** Plan görünümünde dönen süpürme ve ilk işaretler belirir. Üst şeritteki
**gecikme** göstergesi kayıtlı zaman çizgisinden sapmayı verir; sıfır, birebir yeniden
ürettiğiniz anlamına gelir.

**4. Üzerine gelin.** Bir ölçüm noktasının, bir kapı alanının, bir izin üzerine gelin;
menzil, açı, sınırlar, iz numarası çıkar.

**5. Dondurun.** Plan başlığındaki **Canlı** düğmesi. Resim olduğu gibi kalır, işaretler
solmaz, üzerine gelmek çalışmaya devam eder. Gateway bu sırada almayı sürdürür.

**6. Duraklatın, düzenleyin, devam edin.** Henüz gönderilmemiş bir mesajın alanını
değiştirip **Uygula** deyin, sonra **Devam**. Kalan mesajların gönderim anları yeni zaman
çizgisinden yeniden hesaplanır (FR-14).

**7. Zaman çizgisine bakın.** Gönderilenle alınan, gerçekleşme sırasına göre iç içe. Bir
`DetectionReport`'un çıkışını ve ürettiği `MeasurementReport`'un dönüşünü ancak orada yan
yana görürsünüz (FR-32).

Yükleme ve **Başlat** bilerek elle yapılır (FR-6, FR-11): hangi uyarımın canlı bir bağlantıya
ne zaman gideceği bir karardır, bir servisin açılmasının yan etkisi değil. Tekrarlanabilir
bir gösterim ya da CI koşusu için gateway açılışta bir dosya yükleyebilir; **Başlat** yine
sizde kalır:

```bash
DKM_SESSION_PRELOAD=samples/radar-demo-180s.in.bin ./mvnw quarkus:dev
```

## Uçtan uca ne oluyor

```
 ikili dosya                                                        tarayıcı
      │                                                                 ▲
      ▼                                                                 │
 SessionService ─► MessageSet ─► ReplayPlan ─► PlaybackEngine ─┐        │ REST + /ws/events
 (yığın dışı arena, önceden kodlanmış)        (tek pacer thread)│        │ (insan hızında)
                                                               ▼        │
                                            RSP · RSM · CRM  ───────────┤
                                                   ▲    │               │
                                            DKM ───┘    ▼               │
                                                  FrameSplitter         │
                                                        │               │
                                          ┌─────────────┴──────────┐    │
                                          ▼                        ▼    │
                                   CaptureService            VizRing ───┴─► VizPublisher
                                   (çözer, listeler)      (48 baytlık kayıt)      │
                                                                                  ▼
                                                                        /ws/viz ─► WebGL2
                                                                        (ikili, React'e girmez)
```

**Uyarım yolu.** Dosya tek bir yığın dışı arenaya kopyalanır ve mesajlar *koşu başlamadan*
kodlanır. Pacer'ın iç döngüsünde kodlama, tahsis veya şema işi yoktur; aynı anda vadesi gelen
bitişik mesajlar tek bir yazmaya birleştirilir. Üç link tek bir replay saatinden yürür.

**Alım yolu.** Üç iş yapar ve döner: yakalama arenasına kopyala, 48 baytlık örneği kilitsiz
bir halkaya bırak, sokete geri dön. Çözümleme, JSON, disk ve WebSocket'in tamamı soketin hiç
beklemediği başka iş parçacıklarında olur (NFR-6).

Ayrıntılar: [dkm-gateway/README.md](dkm-gateway/README.md) ve
[dkm-console/README.md](dkm-console/README.md).

## Üzerine kurulan doğrulanmış gerçekler

- **Tel formatı**: sabit 40 baytlık `MsgHeader` (beş `std::size_t`), hemen ardından gövde.
  Yerel bayt sırası, ayrı bir serileştirme adımı yok. `msg_length` başlık dahil toplam
  boyuttur ve çerçeveleme için tek yetkili alandır. İkili dosya biçimi ile tel biçimi aynıdır.
- **Topoloji**: RSP (5001), RSM (5002) ve CRM (5003) için üç ayrı TCP linki, her modül için
  bir tane, her biri bağımsız olarak çift yönlü.
- **DKM istemci taraftır.** Her link için kendi açılışında bir kez bağlanır, tekrar denemez.
- **Linkler arası bağımlılık gerçektir.** Bir RSP `DetectionReport`, bir RSM `BeamReport`'un
  önceden duyurmuş olması gereken `beam_id` alanını taşır; duyurulmamışsa DKM onu sessizce
  düşürür. Üç link bu yüzden tek bir paylaşılan replay saatinden yürütülür.

Bunlar güvene dayanmıyor, reponun kendi çıktılarına karşı doğrulanıyor. Gateway'in test
paketi [`dkm-simulator/input.bin`](dkm-simulator/input.bin) dosyasını çözüp `bin_gen`'in
yazdığıyla, [`output.bin`](dkm-simulator/output.bin) dosyasını çözüp `mock_r`'ın gerçekten
hesapladığıyla karşılaştırıyor. `samples/` altındaki demo ikilisinde de 3 128 ölçümün tamamı,
DKM'nin `processing.cpp` kurallarının bağımsız bir uygulamasıyla bire bir tuttu.

## Ölçüm

İki takım, iki ayrı soru.

**Tek geçişli ölçüm** — azami hız, sürdürülebilir hız, görüntü gecikmesi. Çıktısı
`benchmark-report.md`:

```bash
./scripts/benchmark.sh --size 1G --rate 20000
```

**Hız merdiveni** — asıl soru bu. Tek bir azami hız rakamı tartışılır; sorulması gereken,
sabit bir hız teklif edildiğinde kaydın ne kadar sadık yeniden üretildiğidir. Konteynerde,
native ikili üzerinde, CPU ve belleği sabitlenmiş halde, seviye başına üç koşu. Çıktısı
[`benchmark-ladder.md`](benchmark-ladder.md):

```bash
./scripts/bench/ladder.sh
```

| Teklif edilen | Ulaşılan | Bant genişliği | Sapma p50 / p99 | Zirve RSS | Tekrarlanabilir |
|---:|---:|---:|---:|---:|:--:|
| 10 000 msg/s | 10 000 | 1,6 MiB/s | 19 µs / 0,137 ms | 194 MiB | evet |
| 50 000 msg/s | 50 001 | 8,1 MiB/s | 16 µs / 0,155 ms | 601 MiB | evet |
| 100 000 msg/s | 100 004 | 16,3 MiB/s | 28 µs / 0,173 ms | 1 020 MiB | evet |
| 250 000 msg/s | 250 020 | 40,6 MiB/s | 51 µs / 238,8 ms | 2 963 MiB | hayır |
| 500 000 msg/s | 500 037 | 81,3 MiB/s | 74 µs / 406,0 ms | 3 105 MiB | hayır |
| 1 000 000 msg/s | 1 000 166 | 162,5 MiB/s | 102 µs / 35,0 ms | 4 061 MiB | hayır |

Üç cümlede: teklif edilen hız **her seviyede birebir tutturuldu** (±%0,0) ve medyan mesaj her
hızda zamanında. **Yazma duraklaması her seviyede sıfır** — soket hiç geri basınç uygulamadı,
yani taşıma katmanı darboğaz değil. Kırılma 250 000 msg/s'de kuyrukta başlıyor; sebebi
ölçüldü, [aşağıda](#bilinen-sınırlar).

Diğer ölçülenler:

| | |
|---|---|
| Görselleştirme gecikmesi | sunucudan tarayıcıya p50 **0 ms**, p99 **1 ms** |
| Native ikili | **0,060 s** açılış, **64 MiB** bellek, 54 MB tek dosya |
| Testler | 40 birim/entegrasyon + paketlenmiş uygulamaya karşı 2 |

Rapor kendi satırlarının hangisinin güvenilir olduğunu söyler: koşuları birbirinden dörtte
birden fazla ayrılan seviyeler açıkça işaretlenir. Ölçüm yönteminin gerekçeleri ve neyin
ölçülmediği [`scripts/bench/README.md`](scripts/bench/README.md) içinde; ham kanıt
`benchmark/ladder/` altında.

Canlı metrikler `/q/metrics` adresinde Prometheus formatında. Hazır yapılandırılmış Grafana
panosuyla:

```bash
docker compose -f scripts/observability/docker-compose.yml up -d
```

Ardından <http://localhost:3000>. Giriş yok, pano hazır geliyor.

## İster karşılama

Otuz üç işlevsel, altı işlevsel olmayan ister. Üçü karar gerektirdi, çünkü ister dokümanının
kendisi (§8) o noktaları açık bıraktı; kararlar ve gerekçeleri
[dkm-gateway/README.md](dkm-gateway/README.md#açık-soruların-kararları) içinde.

| İster | Nerede |
|---|---|
| **G1 / FR-1..5, NFR-1** şema motoru: çözme, kodlama, doğrulama | `dkm-gateway` `schema/` içinde `SchemaCompiler`, `MessageCodec` |
| **FR-5a** `mock_r` / `bin_gen` / `c_sim` karşısında doğrulama | `GroundTruthDecodeTest` |
| **§8 arayüz senkronizasyonu** | `HeaderSchemaGenerator` + `SchemaDriftTest`. Başlıklarla şema ayrışırsa build kırılır |
| **G2 / FR-8, FR-30** alan alan düzenleme | `dkm-console` `FieldEditor.tsx`, tamamen şemadan üretilir |
| **FR-6, FR-10** ikili yükleme ve kaydetme | `MessageSet`, `SessionResource` |
| **FR-9** açık zamanlamayla araya ekleme | `SessionService.insert` |
| **FR-11..15** playback kontrolü | `PlaybackEngine`, `ReplayClock` |
| **FR-13** tek paylaşılan replay saati | `ReplayClock` + `ReplayClockTest` + `EndToEndReplayTest` |
| **FR-16..18** üç link üzerinde TCP sunucusu | `LinkRegistry`, `Link`, `FrameSplitter` |
| **FR-19..21** çıktı yakalama | `CaptureService`, `CaptureResource` |
| **FR-22..24** mesaj kütüphanesi | `MessageLibrary`. Düz JSON, şema özetiyle bayatlık kontrolü |
| **G8 / FR-25..28** görselleştirme | `viz/` + `visualization.json`; `dkm-console` `gl/` |
| **G9 / FR-27** iz korelasyonu | `Prediction.track_id` → `VizExtractor` → `Scene` |
| **G10 / NFR-6** sınırlı telden piksele gecikme | `VizRing` → `VizPublisher` → `VizHub`. Ölçülür, konsolda gösterilir, Prometheus'a aktarılır |
| **FR-29** süzülebilir *ve* sıralanabilir listeler | `MessageSort`. Sunucu tarafında, yani sayfayı değil tüm seti sıralar |
| **FR-31** girdi / geçmiş / çıktı ayrımı | Renk kodu ve davranış; yakalanan çıktının düzenleme yolu hiç yoktur |
| **FR-32** tek kronolojik gönderim/alım izi | `TraceResource`, listelerin kurulduğu aynı kayıtlardan birleştirilir |
| **NFR-3** performans | `scripts/bench/` merdiveni; sonuçlar `benchmark-ladder.md` |
| **NFR-4** bayt düzeyinde birebir gidiş dönüş | `MessageCodec` mevcut baytların üzerine yazar; `CodecRoundTripTest` |
| **NFR-5** bozuk veri açıkça raporlanır, sessizce yorumlanmaz | `MessageSet.describe`, `FrameSplitter.DesyncException` |

## Depo düzeni

```
dkm-gateway/          Quarkus servisi  (bkz. kendi README'si)
dkm-console/          React konsolu    (bkz. kendi README'si)
dkm-simulator/        C++ iskelesi     (teslim edilen iş değil; bkz. kendi README'si)
  req.md                ister dokümanı
  input.bin/output.bin  doğruluk kaynağı — yeniden üretilmemeli
samples/              hazır uyarım/yakalama ikilileri
scripts/
  demo.sh               üçünü doğru sırayla ayağa kaldırır
  benchmark.sh          tek geçişli ölçüm
  bench/                hız merdiveni takımı
  observability/        Prometheus + Grafana
benchmark/ladder/     merdiven koşularının ham çıktısı
data/library/         mesaj kütüphanesinin deposu (çalışma anı durumu)
```

## Geliştirme

```bash
# gateway
cd dkm-gateway
./mvnw test                        # 40 test
./mvnw verify -DskipITs=false      # paketlenmiş uygulamaya karşı 2 test daha
./mvnw package                     # target/quarkus-app/ içinde çalıştırılabilir jar
./mvnw package -Dnative            # native ikili, JVM gerekmez
```

```bash
# konsol
cd dkm-console
npx tsc -b && npx oxlint src       # tip denetimi ve lint
npm run build                      # dist/ altında statik paket
```

Native ikili konteynerde derlenir (`-Dquarkus.native.container-build=true`), yani üretilen
dosya Linux'a aittir ve macOS'ta çalıştırılamaz. `SmokeIT` bu yüzden Linux'ta veya CI'da
koşulmalıdır.

## Bilinen sınırlar

**Ölçekleme.** Mesaj başına indeks kaydı yığında bir Java nesnesi, dolayısıyla bellek mesaj
sayısıyla doğrusal büyüyor — ölçülen mesaj başına ~400 bayt. Sonucu 250 000 msg/s üstünde
gecikme kuyruğunun açılması: 500 000 msg/s'lik bir koşuda 42 GC toplaması, 7'si tam,
duraklamalar azami **1031 ms**. Native imaj tek iş parçacıklı Serial GC ile geliyor ve
GraalVM'in topluluk sürümünde native için Serial dışında yalnızca Epsilon var, yani bu
profilden ayar yaparak çıkılamaz. Yapılacak değişiklik sütunlu bir indeks: mesaj başına
nesneyi kaldırıp paralel ilkel dizilere taşımak.

**Gerçek DKM'ye karşı denenmedi.** `mock_r` gerçek hedefin bağlantı davranışını taşıyor
(istemci taraflı, tek denemeli, üç ayrı link) ve doğrulama onun kendi çıktısına karşı
yapıldı, ama bu bir varsayım (§7, A4). Gerçek donanıma erişim olduğunda ilk yapılacak iş
bunu tekrarlamak.

**Kimlik doğrulama yok.** Şu hâliyle yerel, tek kullanıcılı bir araç olarak konumlanmış
durumda.

Konsolun kendi yapabildikleri ve yapamadıklarının listesi
[dkm-console/README.md](dkm-console/README.md#bu-arayüzün-yapmadıkları) içinde.
