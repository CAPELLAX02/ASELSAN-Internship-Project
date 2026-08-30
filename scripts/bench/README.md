# Ölçüm takımı

Gateway'in performansını, sonucuna itiraz edilebilecek bir biçimde değil,
denetlenebilecek bir biçimde ölçmek için.

```bash
scripts/bench/ladder.sh
```

Native ikiliyi derler, ölçüm imajını kurar, hız merdivenini koşar ve
`benchmark-ladder.md` dosyasını yazar. Ham çıktılar `benchmark/ladder/` altında
kalır: seviye başına birer JSON, birer `.hgrm` dağılım dosyası, bir de ortamın
kendisi.

| Seçenek | Varsayılan | Ne işe yarar |
|---|---|---|
| `--levels "10000 100000"` | altı basamak | teklif edilen hızlar, mesaj/s |
| `--runs 3` | 3 | seviye başına koşu; yayılım bundan çıkar |
| `--cpus 8` / `--memory 8g` | 8 / 8g | konteynere verilen, rapora yazılan |
| `--heap 6g` | 6g | gateway'e verilen `-Xmx` |
| `--gc-level 500000` | 500000 | merdiven bitince bu seviyeyi bir kez de GC kaydıyla koşar |
| `--skip-build` | kapalı | native ikili elde varsa yeniden derleme |

## Neden bu şekilde

**Sorulan soru "ne kadar hızlı gidebiliyor" değil.** Bir replay aracı saniyede
bir gigabayt taşıyıp yine de yanlış olabilir: kendisine verilen kaydı yeniden
üretmiyorsa, hızlı biçimde yanlıştır. Bu yüzden her seviye *sabit bir hız teklif
eder* ve ölçülen şey, o hızın diğer uçtan ne kadar sadık çıktığıdır.

**Hızı bir sınırlayıcı dayatmıyor, dosyanın kendisi dayatıyor.** Her mesaj hangi
anda kaydedildiğini başlığında taşır, pacer da saati o ana geldiğinde gönderir.
Yani üretim kodunda bir ölçüm anahtarı yok, ölçülen yola fazladan hiçbir şey
girmiyor, ve "teklif edilen hız" gateway'in inanmak zorunda olduğu bir şey değil
girdinin bir özelliği.

**Sapmayı gateway'in kendisi bildirmiyor.** Peer telden okur: başlıktaki zaman
damgası mesajın ne zaman gitmesi gerektiğini söyler, peer bunu gerçekte ne zaman
geldiğiyle karşılaştırır. Duraklama sırasında gelen mesajlar da sayılır. Çoğu
gecikme rakamını olduğundan iyi gösteren atlama tam olarak budur: yük üreteci
takıldığında örnek almayı da bırakır ve kötü anları hiç görmez.

**Sıfır, "en hızlı transit" demek.** Orijin ilk gelen mesajdır, ama ilk mesaj ilk
flush'ın bedelini öder; ona göre ölçülen bir koşu baştan sona sabit bir miktar
"erken" görünür. Bu, saatin nereden başlatıldığının artefaktıdır, gateway'in
yaptığı bir şey değil. Bu yüzden dağılım gözlenen en hızlı transite göre yeniden
temellendirilir. `ping`'in kendi minimumunu bildirmesiyle aynı gerekçe.

**Gateway ile yük üreteci aynı konteynerde.** İkisini ayrı konteynerlere koymak
Docker'ın köprüsünü ve userland proxy'sini ölçüm yoluna sokardı; rapor o zaman
gateway'i değil onları anlatırdı. Tek cgroup olması da koşuya verilen CPU ve
belleğin, sayıların ait olduğu CPU ve bellek olmasını sağlar.

**Tek koşu bir ölçüm değil.** JIT durumu, sayfa önbelleği ve makinenin o sırada
yaptığı başka işler bir throughput rakamını tek başına üçte bir oynatır. Rapordaki
her sayı koşuların medyanı, yanında da aralarındaki yayılım.

**Rapor kendi satırlarının hangisinin güvenilir olduğunu söyler.** Bir seviyenin
koşuları p99'da birbirinden dörtte birden fazla ayrılıyorsa oradaki medyan bir
ölçüm değil bir aralığın ortasıdır, ve tabloda "Tekrarlanabilir: **hayır**" diye
işaretlenir. Bunu gizlemek raporu güçlendirmez; kontrol eden ilk okuyucuda
tamamen kaybettirir.

**Kuyruk tahmin edilmez, okunur.** Merdiven bittikten sonra tek bir seviye
`-XX:+PrintGC` ile bir kez daha koşulur ve toplayıcının kendi kaydı raporun
"Kuyruk nereden geliyor" bölümüne çevrilir. Kayıt merdivenin kendi sayılarına
karışmasın diye bu geçiş ayrı bir alt dizine yazar: kaydın kendisi de sıcak yola
biner.

## Parçalar

| Dosya | Ne yapar |
|---|---|
| `ladder.sh` | host tarafı: native derleme, imaj kurma, konteyneri koşturma, rapor |
| `ladder-run.sh` | konteyner içi: seviyeleri gezer, seviye başına JSON yazar |
| `Bench.java` | `gen` uyarım üretir, `peer` DKM yerine geçip ölçer, `viz` görüntü gecikmesini ölçer |
| `sample-proc.py` | gateway sürecinin CPU ve RSS'ini yarım saniyede bir örnekler |
| `report.py` | JSON'ları Türkçe rapora çevirir, GC kayıtlarını çözümler |
| `Dockerfile.bench` | ölçüm imajı |

`Bench.java` derleme adımı olmadan kaynaktan çalışır ve hiçbir bağımlılığı
yoktur. İçindeki histogram da bu yüzden orada: HdrHistogram'ın kova düzenini ve
dosya biçimini kullanır, böylece dağılımlar standart araçlarla yeniden
çizilebilir, ama sınıf yolunda bir jar gerektirmez. Kaydedilen değerin %0,1'inden
küçük bir hatayla, sabit bellekte çalışır; kendini her açılışta sınar, çünkü
yanlış bir kova hesabı sessizce her yüzdeliği kaydırırdı.

## Tek seferlik ölçüm

Merdivene ihtiyaç yoksa eski, tek geçişli betik yerinde duruyor:

```bash
scripts/benchmark.sh
```

Üç soruyu yanıtlar: azami hız, sürdürülebilir hız, ve görüntünün tarayıcıya
ulaşma süresi. Çıktısı `benchmark-report.md`.

## Bu takımın ölçmediği şeyler

Rapor bunları kendi sonunda da yazar, ama önden bilmekte fayda var:

- **Gerçek bir ağı.** Ölçüm konteyner loopback'i üzerinde; MTU 65536, tipik bir
  Ethernet arayüzünde 1500. Gerçek bir NIC'te bant genişliği düşer, gecikme
  dağılımının şekli büyük ölçüde korunur.
- **DKM'nin işlem maliyetini.** Peer gövdeleri çözmez; ölçülen gateway'in
  gönderme yolu.
- **Tarayıcıya kadar olan yolu.** Görüntü gecikmesi ayrı ölçülür (`Bench viz`).
- **Uzun süreli davranışı.** Seviye başına on ila otuz saniye; parçalanma ve
  termal kısıtlama bu pencerede görünmez.
- **Birden çok tüketiciyi.** Tek peer, tek görüntü abonesi.
