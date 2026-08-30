# Gateway hız merdiveni

Sabit teklif edilen hızlarda ölçüm. Sorulan şey “ne kadar hızlı gidebiliyor” değil, “verilen kaydı ne kadar sadık yeniden üretiyor”. Hız, gateway'e bir sınırlayıcı konarak değil uyarım dosyasının kendi zaman damgalarıyla dayatılıyor; yani üretim kodu bir ölçüm yapıldığını bilmiyor ve ölçülen yola fazladan hiçbir şey girmiyor.

## Ortam

| | |
|---|---|
| Çekirdek | 6.10.14-linuxkit / aarch64 |
| Konteynere verilen | 8 CPU, 8g bellek |
| cgroup CPU / bellek | `800000 100000` / `8589934592` |
| Gateway | native ikili, `-Xmx6g` |
| Loopback MTU | 65536 |
| Seviye başına koşu | 3 |

Gateway ve yük üreteci aynı konteynerde, aynı loopback üzerinde. İkisini ayrı konteynerlere koymak Docker'ın köprüsünü ve userland proxy'sini ölçüm yoluna sokardı; o zaman rapor gateway'i değil onları anlatırdı. Tek cgroup olması da koşuya verilen CPU ve belleğin, sayıların ait olduğu CPU ve bellek olmasını sağlıyor.

## Sonuçlar

Sapma sütunu, gözlenen en hızlı transite göre. Sıfır “bu makinenin becerebildiği kadar iyi” demek; üstündeki her yüzdelik açıklanması gereken oynamadır.

| Teklif edilen | Ulaşılan | Bant genişliği | Sapma p50 / p99 / p99,9 / azami | CPU | Zirve RSS | Mesaj başına | Tekrarlanabilir |
|---:|---:|---:|---:|---:|---:|---:|:--:|
| 10 000 msg/s | 10 000 msg/s <sub>±0,0%</sub> | 1,6 MiB/s | 0,019 / 0,137 <sub>±21%</sub> / 5,73 / 20,3 ms | 103% | 194 MiB | 678 B | evet |
| 50 000 msg/s | 50 001 msg/s <sub>±0,0%</sub> | 8,1 MiB/s | 0,016 / 0,155 <sub>±24%</sub> / 8,11 / 54,9 ms | 105% | 601 MiB | 421 B | evet |
| 100 000 msg/s | 100 004 msg/s <sub>±0,0%</sub> | 16,3 MiB/s | 0,028 / 0,173 <sub>±25%</sub> / 4,33 / 22,4 ms | 107% | 1 020 MiB | 357 B | evet |
| 250 000 msg/s | 250 020 msg/s <sub>±0,0%</sub> | 40,6 MiB/s | 0,051 / 238,848 <sub>±128%</sub> / 502,53 / 626,3 ms | 112% | 2 963 MiB | 415 B | **hayır** |
| 500 000 msg/s | 500 037 msg/s <sub>±0,0%</sub> | 81,3 MiB/s | 0,074 / 406,016 <sub>±110%</sub> / 537,09 / 663,9 ms | 121% | 3 105 MiB | 408 B | **hayır** |
| 1 000 000 msg/s | 1 000 166 msg/s <sub>±0,0%</sub> | 162,5 MiB/s | 0,102 / 35,040 <sub>±101%</sub> / 54,34 / 143,1 ms | 134% | 4 061 MiB | 427 B | **hayır** |

**Son sütunu okumadan tabloyu kullanmayın.** 250 000 msg/s, 500 000 msg/s, 1 000 000 msg/s seviyelerinde koşular birbirinden dörtte birden fazla ayrılıyor; oradaki medyan bir ölçüm değil bir aralığın ortası. Sebebi ölçüm yönteminde değil ölçülen şeyde: mesaj başına düşen bellek sabit ve mesaj sayısıyla doğrusal büyüyor, dolayısıyla yüksek hızlarda gateway'in yerleşik kümesi konteynere verilen belleğe yaklaşıyor ve geri kazanım başlıyor. Sapmanın oradaki kuyruğu gateway'in gönderme yolunu değil, o baskıyı anlatıyor.

Aynı cümlenin üretim tarafındaki karşılığı da bu: bu hızlar bu bellek profiliyle ölçülemiyorsa, aynı profil ile çalıştırılamaz da. Mesaj başına düşen bayt sütunu, düzeltilmesi gereken şeyin kendisi.

“Ulaşılan” gateway'in kendi sayacı değil, peer'in telden saydığı. Sapma da öyle: her mesajın başlığındaki zaman damgası, o mesajın ne zaman gitmesi gerektiğini söylüyor; peer bunu gerçekte ne zaman geldiğiyle karşılaştırıyor. Duraklama sırasında gelen mesajlar da sayılıyor — çoğu gecikme rakamını olduğundan iyi gösteren atlama tam olarak budur.

Mesaj karışımı: ortalama 170,4 bayt, link dağılımı RSP %80, RSM %10, CRM %10. Uyarım ağırlıklı olarak `DetectionReport` (192 bayt), yanında her hüzme için bir `BeamReport` (72 bayt) ve bir `Prediction` (96 bayt). Bu, `samples/` altındaki gerçekçi senaryolardan daha bayt yoğun bir karışım: orada hüzme raporları baskın ve mesaj başına ortalama 104 bayta iner. Yani buradaki msg/s ile oradaki msg/s aynı bant genişliği demek değil; kıyaslanacaksa bayt/s kıyaslanmalı.

Yön dağılımı, aynı ölçümün ilk mesaja göre işaretli hali:

| Seviye | Erken gelen | Geç kalan | Geç kalanların p99'u |
|---:|---:|---:|---:|
| 10 000 msg/s | 298 177 | 1 113 <sub>%0,37</sub> | 17,0 ms |
| 50 000 msg/s | 1 488 065 | 8 405 <sub>%0,56</sub> | 16,9 ms |
| 100 000 msg/s | 2 981 522 | 11 428 <sub>%0,38</sub> | 10,8 ms |
| 250 000 msg/s | 7 249 055 | 233 335 <sub>%3,12</sub> | 519,7 ms |
| 500 000 msg/s | 7 364 857 | 616 363 <sub>%7,72</sub> | 539,1 ms |
| 1 000 000 msg/s | 9 196 064 | 780 456 <sub>%7,82</sub> | 54,8 ms |

## Düşme muhasebesi

TCP'de paket kaybı ölçmek anlamsız, hele loopback'te. Anlamlı olan, boru hattında bir şeyin nerede ve hangi politikayla düştüğü.

| Seviye | Yazma duraklaması | Atlanan kare | Düşen örnek | Yakalama taşması | Kalan gecikme |
|---:|---:|---:|---:|---:|---:|
| 10 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |
| 50 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |
| 100 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |
| 250 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |
| 500 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |
| 1 000 000 msg/s | 0 | 0 | 0 | 0 | 0,0 ms |

Yazma duraklaması, soketin geri basınç uyguladığı ve pacer'ın beklediği anların sayısı; sıfırdan büyük olması bir hata değil, o seviyede taşımanın sınırına gelindiğinin işareti. Görüntü tarafındaki atlama ve düşme ise bilinçli politika: alma yolu hiçbir koşulda görüntü için beklemez.

## Kuyruk nereden geliyor

Yukarıdaki kuyruğu şekline bakıp tahmin etmek yerine toplayıcının kendi kaydını okuduk. `GCLOG=1` ile koşulan seviyeler:

| Koşu | Toplayıcı | Toplama | Tam | Duraklama p50 / p90 / azami | Toplam duraklama |
|---|---|---:|---:|---:|---:|
| 500000-run1 | Serial GC | 42 | 7 | 32,5 / 110,5 / 1 031,0 ms | 3 405 ms |

Native imaj **Serial GC** ile geliyor: tek iş parçacıklı, durdur-ve-topla. Çok gigabaytlık bir yığında tam toplama zaten yarım saniye sürer, ve tabloda görülen tam da bu. Yığının o boyuta gelme sebebi de belli: mesaj başına düşen dizin kaydı yığında bir nesne, dolayısıyla bellek mesaj sayısıyla doğrusal büyüyor.

Zincir baştan sona ölçülmüş durumda: yazma duraklaması sıfır olduğu için taşıma katmanı dışarıda; RSS mesaj sayısıyla doğrusal; toplayıcı bunu tam toplamalarla karşılıyor; sapmanın kuyruğu o duraklamaların boyunda. GraalVM'in topluluk sürümünde native imaj için Serial dışında yalnızca Epsilon var, yani bu profilden ayar yaparak çıkılamaz. Çıkış yolu ayırmayı ortadan kaldırmak.

## Dağılımlar

Her seviye için gecikme dağılımı `benchmark/ladder/deviation-<hız>-run<N>.hgrm` altında, HdrHistogram'ın yüzdelik dağılım biçiminde. Yani buradaki yüzdelikler yeniden çizilebilir; kimsenin bu tabloya güvenmesi gerekmiyor.

## Bu koşu neyi ölçmüyor

- **Gerçek bir ağı.** Ölçüm konteyner loopback'i üzerinde. Loopback MTU'su 65536, tipik bir Ethernet arayüzünde 1500. Gerçek bir NIC üzerinde bant genişliği rakamları düşer; gecikme dağılımının şekli büyük ölçüde korunur.
- **DKM'nin kendi işlem süresini.** Peer, DKM yerine geçer ve gövdeleri çözmez. Ölçülen, gateway'in gönderme yolu; DKM'nin bir mesajı işleme maliyeti değil.
- **Tarayıcıya kadar olan yolu.** Görüntü gecikmesi ayrı ölçülüyor (`Bench viz`), bu tablo yalnızca uyarım yönünü anlatıyor.
- **Uzun süreli davranışı.** Seviye başına on ila otuz saniye. Saatler süren bir koşuda parçalanma ve termal kısıtlama devreye girer, ikisi de burada yok.
- **Aynı anda birden çok tüketiciyi.** Tek peer, tek görüntü abonesi.

