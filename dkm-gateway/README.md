# dkm-gateway

DKM ile operatör konsolu arasındaki Quarkus servisi. Üç TCP linkini, şema motorunu, replay
zamanlamasını, çıktı yakalamayı ve konsolun üzerinde çalıştığı arayüzü yönetir.

```
DKM (TCP istemci)  ──►  üç NetServer  ──►  FrameSplitter  ──►  CaptureService  ──►  REST
        ▲                                          │
        │                                          └──►  VizRing ──► VizPublisher ──► /ws/viz
   PlaybackEngine  ◄──  ReplayPlan  ◄──  MessageSet  ◄──  SessionService  ◄──  REST
```

## Neden Quarkus, ve hız aslında nereden geliyor

Çerçeve seçimi kodun şeklinden daha az önemli, ama önemsiz de değil: TCP katmanı
Vert.x/Netty olduğu için okuma ve yazma bloklamıyor, bağlantı başına thread yok ve servis
tamamen native bir çalıştırılabilire derleniyor: JIT ısınması yok, bellek ayak izi kesirli.
Throughput'u taşıyan asıl şeyler ise şunlar:

- **Mesajlar koşu başlamadan kodlanıyor, koşu sırasında değil.** Tek bir yığın dışı arena'da
  arka arkaya dururlar, böylece pacer'ın iç döngüsü kodlama, tahsis veya şema işi yapmaz.
- **Aynı anda vadesi gelen bitişik mesajlar tek bir yazmaya dönüşür.** Yüksek hızlarda
  throughput'u belirleyen şey syscall sayısıdır ve birleştirme onu düşüren şeydir.
- **Alım yolu sınırlı üç iş yapar ve döner**: yakalama arena'sına kopyala, 48 baytlık örneği
  kilitsiz bir ring'e bırak, sokete geri dön. Çözümleme, JSON, disk ve WebSocket'in tamamı,
  soketin hiç beklemediği başka thread'lerde olur.
- **Sıcak yolda hiçbir şey alanı adıyla aramaz.** `SchemaCompiler` şemayı açılışta bir kez
  byte offsetlerine çevirir; ondan sonrası tam sayı aritmetiğidir.

Geri basınç, uyarım atarak veya sınırsız tamponlayarak değil, pacer'ı soketin drain
handler'ında bekleterek karşılanır: girdi için sadakat, hiç durmamaktan önemlidir.
Görselleştirme akışında bu takas bilinçli olarak terstir: teslim edilemeyen bir örnek
düşürülür ve sayılır, çünkü canlı bir görüntü her kareyi değil en yeni kareyi ister.

## Thread modeli

| Thread | Yapar | Asla yapmaz |
|---|---|---|
| Vert.x event loop'ları (link başına bir tane) | okur, çerçeveler, örnekler, devreder | bloklamaz, JSON'a çözmez, diske dokunmaz |
| `dkm-pacer` (tek, tüm linkler) | replay saatinin sahibi, üç sokete de yazar | kodlama, tahsis |
| `dkm-capture` | liste için çözümler, `output.bin` yazar | sokete dokunmaz |
| `dkm-viz` | ring'leri boşaltır, ikili kareler üretir | tarayıcıyı beklemez |
| `dkm-telemetry` | sayaçları türevler | başka bir şey |

Link başına bir yerine tüm linkler için tek bir pacer olması, FR-13'ün ta kendisidir: tek
bir saati sahiplenen tek bir thread, RSM'in `BeamReport`'unu ona atıf yapan RSP
`DetectionReport`'undan önde tutmak için hiçbir loop'lar arası koordinasyona ihtiyaç duymaz.

## Şema

[`src/main/resources/interface/interface-schema.json`](src/main/resources/interface/interface-schema.json)
tel formatının tek doğruluk kaynağıdır. Modülleri, ortak başlığı, adlandırılmış struct'ları
ve her mesaj tipini alanları, dizi uzunlukları ve sayaç alanlarıyla birlikte tanımlar.
Ayrıca C++ header'larının ifade edemediği şeyleri de: yön, birim, açıklama, enum etiketleri
ve hangi alanın korelasyon kimliği olduğu.

`SchemaCompiler` bunu standart C++ yerleşim kurallarıyla byte offsetlerine çevirir.
Hesapladığı mesaj boyutları `SchemaLayoutTest` ve `GroundTruthDecodeTest` içinde gerçek
kayıtlara karşı doğrulanır.

`size_t` genişliği ve byte sırası **yapılandırmadır**, şema değil (`dkm.wire.*`): bunlar
DKM'nin koştuğu makinenin özellikleridir. 32-bit ya da big-endian bir hedef her mesajdaki
her offseti kaydırır ve bu bir yeniden yazım değil bir ayar değişikliği olmalıdır.

## Açık soruların kararları

İsterlerin §8 bölümü birkaç konuyu açık bırakıyor. Kurmak için karar gereken yerlerde karar
verildi ve gerekçesiyle birlikte burada kayıt altına alındı.

**Arayüz senkronizasyonu: elle bakım, ve bunu kanıtlayan bir üreteç.**
Tek başına kod üretimi, header'ların içermediği şeyi üretemez. Tek başına elle bakım ise
sessizce çürür. Bu yüzden şema elle bakımlı kalır ve `HeaderSchemaGenerator` her build'de
`mock_r/inc/interface/*.h` dosyalarını ayrıştırır; yapısal yarı ayrışırsa `SchemaDriftTest`
kırılır. Böylece bir arayüz değişikliği, sessizce yanlış byte gönderen bir simülatör yerine
farkı yazan kırmızı bir build olur.

```bash
mvn -q exec:java -Dexec.mainClass=com.aselsan.dkm.gateway.schema.HeaderSchemaGenerator \
    -Dexec.args=../dkm-simulator/mock_r/inc/interface
```

**Durdur ile duraklat: ikisi de var, çağıran seçer.**
`POST /api/playback/stop?rewind=true` (varsayılan, konsolun Durdur düğmesinin gönderdiği)
koşuyu başa sarar ve tüm seti yeniden düzenlenebilir yapar. `rewind=false` yerinde keser ve
gönderilmiş mesajları geçmiş olarak bırakır. Paydaşların hangisini istediğini tahmin etmek
yerine ikisi de var; cevap kullanımdan gelsin.

**Duraklatılmışken düzenleme kapsamı: alan değeri, zamanlama, konum ve üyelik.**
Bekleyen mesajlar alan düzenlemesi (`PUT /messages/{id}`), yeniden zamanlama
(`PUT /messages/{id}/timestamp`), açık bir offsetle seçilen konuma ekleme ve silme kabul
eder. Gönderilmiş mesajlar hiçbirini kabul etmez. Pacer koşarken düzenleme tamamen
reddedilir, çünkü gönderilmek üzere olan bir mesaj altından değişmemelidir.

**Mesaj kütüphanesi deposu: düz JSON dosyaları, veritabanı değil.**
Depo, bir mühendisin okuyacağı, elle düzenleyeceği, makineler arasında kopyalayacağı,
diff'te gözden geçireceği ve bir test senaryosunun yanında versiyon kontrolüne koyacağı
birkaç yüz küçük kayıttan ibaret. Bir veritabanı dosyası bunların hepsinden vazgeçip
karşılığında hiç ihtiyaç duyulmayan sorgu performansı verirdi. Her kayıt, kaydedildiği şema
sürümünü ve özetini tutar; böylece eskimiş bir kayıt, artık doğru olmayan bir yerleşimle
gönderilmek yerine işaretlenir.

**Görselleştirme gecikmesi: iddia edilmiyor, ölçülüp gösteriliyor.**
Konsol, sunucunun kendi kare damgasından türetilen canlı bir "telden piksele" değeri
gösterir. Geliştirme kurulumunda 1 ms civarında seyrediyor ve 16 ms'lik kare aralığı artı
bir animasyon karesiyle sınırlı. Paydaşlar bir sayı verdiğinde ayar noktası
`dkm.viz.frame-interval-millis`.

## Yapılandırma

Tamamı [`application.yml`](src/main/resources/application.yml) içinde ve tamamı
değiştirilebilir. Önemli olanlar:

| Özellik | Varsayılan | Ne zaman değiştirirsiniz |
|---|---|---|
| `dkm.wire.size-t-bytes` | `8` | 32-bit hedef |
| `dkm.wire.byte-order` | `LITTLE_ENDIAN` | big-endian hedef (örneğin PowerPC VxWorks) |
| `dkm.links.host` / `dkm.links.port.<MODÜL>` | `0.0.0.0`, şemadaki portlar | DKM başka bir makinede ya da portlar farklı |
| `dkm.schema.path` | classpath | yeniden derlemeden güncellenmiş bir şema dosyası vermek |
| `dkm.viz.path` | classpath | mesaj tiplerinin nasıl çizildiğini değiştirmek |
| `dkm.playback.max-batch-bytes` | `262144` | yazma birleştirme boyutu |
| `dkm.capture.max-messages` | `2000000` | bellekte tutulacak çıktı miktarı |
| `dkm.capture.record-path` | *(kapalı)* | alınan her şeyi anında bir dosyaya aynalamak |
| `dkm.session.preload` | *(kapalı)* | açılışta bir uyarım dosyası yüklemek; **Başlat** yine operatörde kalır |

## Arayüz

| | |
|---|---|
| `GET /api/schema`, `/api/schema/visualization` | arayüz ve çizim kuralları, veri olarak |
| `GET/PUT/POST/DELETE /api/session/messages…` | düzenlenebilir uyarım seti |
| `POST /api/session/load`, `/load-path` · `GET /api/session/export` | binary giriş ve çıkış |
| `GET /api/capture/messages…` · `GET /api/capture/export` | yakalanan DKM çıktısı |
| `GET/POST /api/playback…` | başlat / duraklat / devam / durdur / hız / mod |
| `GET /api/library` · `POST /api/library/{id}/insert` | mesaj kütüphanesi |
| `GET /api/trace` | gönderilen ve gelenin tek kronolojik görünümü (FR-32) |
| `GET /api/status`, `/status/links`, `/status/log` | bağlantı durumu, throughput, oturum kaydı |
| `GET /q/metrics` | Prometheus metrikleri |
| `ws://…/ws/events` | JSON kontrol olayları |
| `ws://…/ws/viz` | ikili görselleştirme kareleri |

### Görselleştirme karesinin yerleşimi

Baştan sona little-endian. 24 baytlık bir başlık, ardından sabit 48 baytlık kayıtlar.
Boyutlar ve hizalama, tarayıcının `DataView` ile okuyup doğrudan WebGL'e verebileceği
şekilde seçildi.

```
başlık  0  u32 sihir 'DKMV'   8  u32 kayıtSayısı
        4  u16 sürüm         12  u32 yukarıdaDüşen
        6  u16 bayraklar     16  f64 sunucuDuvarSaatiMs

kayıt   0  u32 sıra           8  u32 izNo       16 f32 a,b,c,d   32 f64 zamanDamgası
        4  u16 msgId         12  u32 bayraklar                    40 f32 e,f
        6  u8  linkIndeksi
        7  u8  tür
```

`a`–`f` türe göre değişir: bir nokta x, y, mesafe ve açı taşır (hız e ve f'de); bir dilim
başlangıç/bitiş mesafesi ve başlangıç/bitiş açısı; bir dikdörtgen sınırlarını; bir yön ise
uzunluk ve kerterizi.

## Metrikler

Servisin zaten tuttuğu her sayaç `/q/metrics` adresinde yayınlanır. Hepsi *pull* tabanlıdır,
yani bir kazıma birkaç alan okumasına mal olur ve kimse izlese de izlemese de sıcak yolda
hiçbir şey değişmez. Hazır yapılandırılmış bir Prometheus ve Grafana çifti
`scripts/observability` altında:

```bash
docker compose -f scripts/observability/docker-compose.yml up -d
```

İzlenmeye değer metrik **`dkm_playback_lag_milliseconds`**. Saniyedeki byte, telin ne kadar
hızlı aktığını söyler; gecikme ise replay'in kayıtlı zaman çizgisini hâlâ sadık şekilde
yeniden üretip üretmediğini. Bir koşu saniyede bir gigabyte taşıyıp yine de yanlış olabilir
ve bunu söyleyen sayı budur.

Diğerleri:

| Metrik | Ne söyler |
|---|---|
| `dkm_link_bytes_total{link,direction}` | link başına, iki yönde throughput |
| `dkm_link_write_stalls_total{link}` | hızı gateway değil DKM belirliyor |
| `dkm_viz_hold_seconds{quantile}` | telden piksele bütçesinin sunucu tarafındaki yarısı |
| `dkm_viz_dropped_samples_total` | kaybedilen görüntü ayrıntısı, asla veri değil |
| `dkm_capture_overflowed_messages_total` | saklanamayan çıktı |

## Performans

İddia değil, ölçüm. İki takım var ve farklı sorulara cevap veriyorlar.

**Tek geçişli ölçüm** — azami hız, sürdürülebilir hız ve görüntü gecikmesi:

```bash
../scripts/benchmark.sh --size 1G --rate 20000
```

**Hız merdiveni** — asıl soru bu. Sabit bir hız teklif edildiğinde kaydın ne kadar sadık
yeniden üretildiğini ölçer, konteynerde native ikili üzerinde, CPU ve belleği sabitlenmiş
halde, seviye başına üç koşuyla:

```bash
../scripts/bench/ladder.sh
```

Ölçülen (8 CPU / 8 GB konteyner, aarch64 Linux, loopback):

| Teklif edilen | Ulaşılan | Bant genişliği | Sapma p50 / p99 | Zirve RSS | Tekrarlanabilir |
|---:|---:|---:|---:|---:|:--:|
| 10 000 msg/s | 10 000 | 1,6 MiB/s | 19 µs / 0,137 ms | 194 MiB | evet |
| 50 000 msg/s | 50 001 | 8,1 MiB/s | 16 µs / 0,155 ms | 601 MiB | evet |
| 100 000 msg/s | 100 004 | 16,3 MiB/s | 28 µs / 0,173 ms | 1 020 MiB | evet |
| 250 000 msg/s | 250 020 | 40,6 MiB/s | 51 µs / 238,8 ms | 2 963 MiB | hayır |
| 500 000 msg/s | 500 037 | 81,3 MiB/s | 74 µs / 406,0 ms | 3 105 MiB | hayır |
| 1 000 000 msg/s | 1 000 166 | 162,5 MiB/s | 102 µs / 35,0 ms | 4 061 MiB | hayır |

Okunuşu üç cümlede. **Teklif edilen hız her seviyede birebir tutturuldu** (fark ±%0,0) ve
medyan mesaj her hızda zamanında. **Yazma duraklaması her seviyede sıfır**, yani soket hiç
geri basınç uygulamadı — taşıma katmanı darboğaz değil. Kırılma 250 000 msg/s'de kuyrukta
başlıyor ve sebebi ölçülmüş: GC kaydına göre 500 000 msg/s'lik bir koşuda 42 toplama, 7'si
tam, duraklamalar azami **1031 ms**. Native imaj tek iş parçacıklı Serial GC ile geliyor.

Yığının o boyuta gelme sebebi de belli: mesaj başına indeks kaydı yığında bir Java nesnesi,
yani bellek mesaj *sayısıyla* doğrusal büyüyor — ölçülen mesaj başına ~400 bayt. Bunun
sonucu yalnızca gecikme değil, ölçülebilirlik: yüksek seviyelerde koşular birbirinden
dörtte birden fazla ayrılıyor ve rapor o satırları "tekrarlanabilir: hayır" diye
işaretliyor. Aynı cümlenin üretim tarafındaki karşılığı da bu — bu bellek profiliyle
ölçülemeyen hız, aynı profille çalıştırılamaz da.

Yapılacak değişiklik sütunlu bir indekstir: mesaj başına nesneyi kaldırıp paralel ilkel
dizilere taşımak. GraalVM'in topluluk sürümünde native imaj için Serial dışında yalnızca
Epsilon olduğundan, bu profilden ayar yaparak çıkış yolu yok.

Ölçüm yönteminin gerekçeleri, neyin ölçülmediği ve ham kanıt
[`../scripts/bench/README.md`](../scripts/bench/README.md) ile
[`../benchmark-ladder.md`](../benchmark-ladder.md) içinde.

## Derleme ve test

```bash
./mvnw test                              # 40 test
./mvnw verify -DskipITs=false            # paketlenmiş uygulamaya karşı 2 test daha
./mvnw package                           # target/quarkus-app/ içinde çalıştırılabilir jar
./mvnw package -Dnative                  # native binary, JVM gerekmez
```

Bilinmeye değer testler:

- `GroundTruthDecodeTest` — bu reponun `input.bin` ve `output.bin` dosyalarını çözer ve
  değerlerin `bin_gen`'in yazdığıyla ve `mock_r`'ın gerçekten hesapladığıyla eşleştiğini
  doğrular. Ayrıca `input.bin` içindeki eskimiş `Prediction` mesajının (88 bayt, `track_id`
  eklenmeden önce yazılmış) sessizce yanlış çözülmek yerine **raporlandığını** doğrular.
- `SchemaDriftTest` — yukarıda anlatılan arayüz senkronizasyon kontrolü.
- `FrameSplitterTest` — aynı dosya, 1 bayttan başlayarak her chunk boyutunda aynı şekilde
  çerçeveleniyor mu.
- `EndToEndReplayTest` — DKM yerine geçen bir istemci üç porta da bağlanır, replay'i alır ve
  cevabı yakalanır; linkler arası sıralamayı ve duraklat/düzenle/devam et davranışını
  doğrular.
- `WebSocketStreamTest` — iki kanalda da gerçek bir istemci. Bu test, REST tamamen doğru
  çalışırken her push'un sessizce başarısız olmasına yol açan bir hata yüzünden var.
- `ListAndTraceTest` — süzülmüş bir set üzerinde sıralama, ve bir uyarımla onun ürettiği
  cevabı doğru sırada gösteren kronolojik iz.
- `SmokeIT` — *paketlenmiş* uygulamaya karşı koşar (`./mvnw verify -DskipITs=false`, ya da
  `-Dnative` altında otomatik olarak). Native image bir kez şema kaynakları olmadan
  üretildiği için var: JVM derlemesi kusursuzdu ve native binary açılışın ilk satırında
  ölüyordu. macOS'ta container ile üretilmiş bir binary çalıştırılamaz; bu testi Linux'ta
  veya CI'da koşturun.
