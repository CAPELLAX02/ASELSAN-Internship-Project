# dkm-console

Operatör konsolu: mesaj listeleri, şemadan üretilen alan editörü, transport kontrolleri ve
canlı plan görünümü.

React 19 · Vite 8 · TypeScript · Tailwind 4 · elle yazılmış WebGL2. Grafik kütüphanesi yok,
bileşen kütüphanesi yok, sanallaştırma kütüphanesi yok, i18n çatısı yok. Normalde bunları
gerektiren işlerin (radar, uzun liste, form, iki dil) her birinin genel amaçlı çözümün
karşılamadığı bir isteri var ya da bir çatının kazandıracağından fazlasına mal olacak kadar
küçükler. Yirmi kaynak dosyası.

## Çalıştırma

```bash
npm install
npm run dev          # http://localhost:5173, /api ve /ws gateway'e proxy'lenir
```

Gateway `http://127.0.0.1:8080` adresinde bekleniyor; başka yere yönlendirmek için
`DKM_GATEWAY=http://sunucu:port npm run dev`. `npm run build` statik bir paket üretir;
`/api` ve `/ws` gateway'e yönlendirildiği sürece herhangi bir sunucu bunu servis edebilir.

Konsol tek başına anlamlı değildir: gateway ayakta olmalı ve DKM ona bağlanmış olmalı.
Bağlantı yoksa arayüz bunu söyler ve kendi kendine yeniden dener.

## Yapı

```
src/
  api/         REST istemcisi, kendini toparlayan WebSocket'ler, şemanın servis edildiği tipler
  gl/          Scene (tipli dizilerdeki durum), Renderer (WebGL2), tooltip için isabet testi
  i18n/        Türkçe ve İngilizce sözlükler
  store/       zustand. Yalnızca bir insanın okuyabileceği hızda değişen şeyler
  components/  TopBar · MessagePanel · InspectorPanel · FieldEditor · RadarView · PlanTooltip
               · LibraryPanel · LogPanel · Tour
```

## Ekranın dört bölgesi

**Üst şerit.** Bağlantı rozetleri, transport düğmeleri, hız kaydırıcısı ve mod seçimi,
ilerleme ve gecikme göstergesi, dosya işlemleri, tema ve dil. Gecikme göstergesi burada
duruyor, çünkü bir koşu boyunca izlenmesi gereken tek sayı odur: replay'in kayıtlı zaman
çizgisinden sapması.

**Sol panel — üç liste.** *Uyarım* gönderdikleriniz, *Yakalama* geri gelenler, *Zaman
çizgisi* ise ikisini gerçekleşme sırasına göre birlikte gösterir; her satırda bir öncekinden
bu yana geçen süre ile. İlk ikisi "bu sette ne var" sorusunu yanıtlar; "ne oldu, hangi
sırayla" sorusunu yalnızca zaman çizgisi yanıtlar. Bir `DetectionReport`'un çıkışını ve
ürettiği `MeasurementReport`'un birkaç milisaniye sonra dönüşünü ancak orada yan yana
görürsünüz (FR-32).

**Orta — plan görünümü ve oturum kaydı.** Üstte çizim yüzeyi, altta ne gönderildiği, ne
alındığı ve link başına akış hızları.

**Sağ panel — denetçi.** Seçili mesajın bütün alanları, düzenlenebilir olduğunda
düzenlenebilir kontrollerle. Yanında yeni mesaj oluşturma ve kütüphane sekmeleri.

## Hiçbir mesaj tipi burada yazılı değil

`FieldEditor.tsx` içinde `DetectionReport`, hüzme ya da açı geçmez. `/api/schema` okunur ve
kontroller bildirilen tiplerden üretilir: bir tam sayı aralığını genişliğinden ve
işaretinden alır, bir enum açılır liste olur, sabit bir dizi canlı uzunluğu sayaç alanı
tarafından yönetilen bir liste olur. Yarın şemaya eklenen bir mesaj tipi bugün çalışan bir
editöre kavuşur; NFR-1'in aslında istediği şey budur.

Aynısı görüntü için de geçerli: renkler, şekiller, koordinat kuralları ve bir işaretin
ekranda ne kadar kalacağı `/api/schema/visualization` üzerinden gelir.

## Görselleştirme akışı React'e hiç girmez

Konsolun etrafında kurulduğu tek tasarım kararı budur.

İkili kareler `/ws/viz` üzerinden gelir ve doğrudan `gl/Scene.ts` içindeki önceden ayrılmış
tipli dizilere çözülür. Örnek başına nesne yok, durum güncellemesi yok, yeniden render yok.
Bir `requestAnimationFrame` döngüsü bu dizileri okur ve GPU'ya yükler; nokta halkası
kirlenen aralığa göre yüklenir, dolayısıyla yoğun bir sahne sakin birine yakın maliyettedir.
React yalnızca sayaçları öğrenir, saniyede dört kez.

Sonuç olarak bir mesaj telden piksele çatıya hiç uğramadan gidebilir. G10/NFR-6'yı bir umut
değil bir tasarım özelliği yapan şey budur. Konsol, sunucunun kendi kare damgasından
türetilen ölçülmüş değeri görüntünün yanında gösterir; yani iddia denetlenebilir. Ölçülen:
sunucudan tarayıcıya p50 0 ms, p99 1 ms.

## Görüntü, DKM'nin kendi geometrisini yeniden üretir

`GateAreaMsg` kutupsal, `ReportingAreaMsg` kartezyendir; bu yüzden biri halka dilimi, diğeri
dikdörtgen olarak çizilir. Dönüşüm `mock_r/src/core/processing.cpp` dosyasından birebir
alınmıştır: `x = mesafe·cos(açı)`, `y = mesafe·sin(açı)`, açı radyan. Bir kapı alanını
dikdörtgen çizmek ya da açıyı pusula kerterizi saymak, operatöre DKM'nin uyguladığından
farklı bir içeride/dışarıda kararı gösterirdi; bu hiç çizmemekten kötüdür (FR-26).

Bir `JammerReport` kendi kerterizini taşımaz, bir hüzme adı taşır. Çizgisi, o hüzme numarası
için en son gelen `BeamReport`'un kerterizinde çizilir; bu, DKM'nin kendi yaptığı aramanın
aynısıdır. Duyurulmamış bir hüzme hiçbir şey çizmez, tıpkı DKM'nin onu düşürdüğü gibi.

Aynı `track_id` değerini paylaşan mesajlar, kuyruğu geçmişe doğru solan tek bir bağlı iz
olarak çizilir; başında bildirilen hıza göre bir vektör bulunur (FR-27).

## Işınların kalıcılığı tipe göre

Dönen bir anten saniyede onlarca hüzme duyurur. Hepsi birkaç saniye ekranda kalırsa görüntü,
altındaki her şeyi gömen bir yıldız patlamasına dönüşür. Bir karıştırıcı işareti ise bir
olaydır ve fark edilecek kadar durmalıdır. Tek bir sayı ikisine birden hizmet edemez, o
yüzden süre `visualization.json` içindeki `style.persistenceMs` alanından gelir: hüzme
350 ms, karıştırıcı 6 s. Bir tipin nasıl çizileceğine zaten o dosya karar veriyordu, ne
kadar duracağına da o karar veriyor.

## Dondurmak zamanı durdurur

İşaretler yaşlandıkça soluyor, dolayısıyla "dondur" örnek almayı kesmekle kalamaz: saati
yürümeye devam eden donmuş bir resim, operatör hâlâ okurken kendini boşaltırdı. Görüntü
duvar saatinden değil, dondurunca duran kendi saatinden yaşlanır. Devam edilince kayma
duraklamanın süresi kadar ileri alınır, böylece her işaret durdurulduğu yaştan devam eder,
sıçramaz. Donmuşken üzerine gelmek çalışmaya devam eder; incelemenin bütün amacı bu.

Gateway bu sırada almaya devam eder. Dondurmak görüntüyü durdurur, koşuyu değil.

## Üzerine gelince değerler

Plan görünümü bir şeyin *nerede* olduğunu göstermekte iyi, *ne* olduğunu göstermekte
zayıftır. Herhangi bir işaretin, alanın, izin veya çizginin üzerine gelmek bu boşluğu
kapatır: bir işaret için menzil ve açı, bir alan için iki sınır ve ne anlama geldiği, bir iz
için numarası ve kaç gözlemden oluştuğu. Tooltip imleci takip eder, anlattığı şeyin üstünü
örtmez ve kenara yaklaşınca taraf değiştirir.

İsabet testi kare döngüsünde yapılır, imleç olayında değil: kare başına en fazla bir geçiş
ve fare hareketi başına hiç React güncellemesi yok.

## Listeler elle pencerelenir

Satır yüksekliği sabittir, yani görünen dilim aritmetiktir. Sayfalar gateway'den gelir ve
orada yalnızca istenen sayfa çözümlenir. Böylece milyonlarca mesajlık bir yakalama tarayıcıya
on mesajlık biri kadara mal olur, sunucuya da bir sayfalık çözümleme.

Sıralama ve süzme de gateway'e sorulur, burada uygulanmaz. Tarayıcı yalnızca tek bir sayfa
tutar, dolayısıyla istemci tarafında sıralamak listeyi değil sayfayı sıralardı: liste
yeterince uzayana kadar çalışıyormuş gibi görünen bir kontrol (FR-29).

## Üç görsel durum, yapısal olarak

Düzenlenebilir uyarım, gönderilmiş geçmiş ve salt okunur yakalama; hem renkle hem denetçinin
neye izin verdiğiyle ayrılır (FR-31). Yakalamanın düzenleme yolu hiç yoktur: devre dışı bir
düğme değil, uç nokta bile yok.

Düzenleme, koşu sürerken tamamen reddedilir. Önce duraklatmak gerekir; gönderilmek üzere olan
bir zaman çizgisini altından değiştirmek sonucu açıklanamaz kılardı. Duraklatıldığında
bekleyen her mesaj yeniden düzenlenebilir olur ve devam edildiğinde gönderim anları yeni
zaman çizgisinden yeniden türetilir (FR-14).

## Tema ve dil

Açık, koyu ve sistemi takip etme, üst şeritteki düğmeden. Renk ölçeği parlaklığa göre değil
**role göre** indekslenmiştir: `ink-950` her zaman sayfa zemini, `ink-100` her zaman en
güçlü metin. Böylece bir tema `index.css` içindeki on bir değerden ibarettir, uygulamanın
hiçbir yerindeki tek bir `className` değişmez.

Nötr renkler soğuk mavi-grinin yerine sıcak tonlardan seçildi. İki sebeple: sıcak bir zeminin
karşısında uzun süre oturmak daha kolaydır, ve spektrumun soğuk ucu tamamen veriye kalır.
Böylece bir ölçüm noktası ya da bir kapı alanı, etrafındaki mobilyayla yarışmaz.

Plan görünümü de temayı takip eder: zeminini ve ızgarasını aynı CSS değişkenlerinden okur ve
açık zeminde toplamalı harmanlamadan normal harmanlamaya geçer. Karanlıkta ışık eklemek bir
plan göstergesini enstrümana benzeten şeydir; beyaz üzerinde ise her işareti zemine
karıştırırdı.

Türkçe ve İngilizce `src/i18n` altında. İngilizce referanstır ve Türkçede eksik bir anahtar
ona düşer, böylece yarım kalmış bir çeviri bozuk değil okunabilir bir arayüz verir. Geliştirme
sırasında eksik anahtar konsola bir kez uyarı yazar. Sayılar da dili takip eder: Türkçe
arayüzde ondalık ayırıcı virgüldür.

## Tipografi

Dört adımlık tek bir ölçek `index.css` içinde: `text-micro` 11px, `text-mini` 12px, gövde
14px, `text-lead` 16px. Bileşenlerin hiçbirinde piksel değeri yazmıyor, dolayısıyla "her şey
bir tık büyüsün" kırk beş sınıf adı değil dört sayı.

İki küçük adım varsayılan olarak daha kalın gelir. Küçük punto ayakta durmak için daha
fazla gövdeye ihtiyaç duyar ve Arial'de yalnızca normal ile kalın var, o yüzden farkı
harcamaya değecek tek yer orası. Gövde metni ayrıca bilerek gri tonlamalı yumuşatma
kullanmıyor: WebKit'in `antialiased` değeri her çizgiyi yaklaşık üçte bir piksel inceltiyor
ve küçük etiketleri soluk gösteren şey buydu.

## Tanıtım

Bu tarayıcı DKM'yi ilk kez bağlı gördüğünde açılır, ilk kareden itibaren atlanabilir ve üst
şeritteki `?` düğmesinden tekrar açılır. Tetikleyici bilinçli: sayfa ilk yüklendiğinde
gösterilen bir tanıtım, henüz hiçbir şey yapamayan bir aracı anlatırdı. Kart anahtarın
altına, sığmazsa üstüne, o da olmazsa yanına yerleşir ve her zaman ekran içinde kalır.

## Kontroller

| | |
|---|---|
| tekerlek | imleç etrafında yakınlaş |
| sürükle | kaydır |
| çift tıklama / **Sığdır** | çizili olan her şeyi ekrana sığdır |
| üzerine gel | o öğenin değerleri |
| **Canlı / Donduruldu** | görüntüyü olduğu gibi dondur; gateway almaya devam eder |
| **Temizle** | yalnızca görüntüyü temizle; yakalanan mesajlara dokunmaz |
| üst şeritteki `?` | tanıtımı yeniden göster |

Tanıtım sırasında: `→` / `Enter` ileri, `←` geri, `Esc` atla.

## Bu arayüzün yapmadıkları

**Bilinçli olarak yok.** Yakalanan çıktı düzenlenemez (DKM'nin gönderdiği şey kanıttır),
koşu sürerken düzenleme yapılamaz, gönderilmiş bir mesaj değiştirilemez, yeni mesaj tipi
arayüzden tanımlanamaz (şema arayüz sözleşmesidir, bir tercih değil), portlar arayüzden
değiştirilemez.

**Henüz yok.** Adım adım geri al/yinele, yakalanan çıktıyı uyarım olarak geri yükleme,
kimlik doğrulama, plan görünümünde harita altlığı, iki nokta arası ölçüm cetveli, görüntüyü
videoya kaydetme.

## Derleme ve denetim

```bash
npm run build        # dist/ altında statik paket
npx tsc -b           # tip denetimi
npx oxlint src       # lint
```
