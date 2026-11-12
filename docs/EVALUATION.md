# Teknik değerlendirmeler

> §1'deki Zemberek sayıları masaüstünde ölçüldü (2026-11-12); cihaz ölçümü Faz 3 başında.
> Diğer sayılar ön tahmindir. Lisanslar kullanılmadan önce kaynağından doğrulanacak.

## 1. Zemberek-NLP mobilde kullanılabilir mi?

Zemberek-NLP (Java, Apache-2.0, GPL-3.0 ile uyumlu) Türkçe için en kapsamlı açık morfolojik
analiz/üretim kütüphanesi. Sorun "doğru mu" değil, "klavye sürecinde taşınabilir mi".

### Masaüstü ölçümü (2026-11-12)

Araç: [`tools/zemberek-spike`](../tools/zemberek-spike/README.md), Zemberek 0.17.1, OpenJDK 21
(x86-64, 4 çekirdek Xeon 2,3 GHz), SerialGC, her koşu taze JVM. Ham çıktı (tüm yapılandırmalar):
[`sonuclar/masaustu-2026-11-12.md`](../tools/zemberek-spike/sonuclar/masaustu-2026-11-12.md).

"Varsayılan" kurulum `createWithDefaults()` ile aynıdır, yalnız önbellek kapalıdır: `build()`
gömülü belirsizlik giderme modelini de (`tr/ambiguity/model-compressed`) yükler. "Modelsiz"
kurulumda bu model yüklenmez; klavyenin ihtiyacı olan kelime analizi aynen çalışır.

| Ölçüm | JIT, varsayılan | JIT, modelsiz | Yalnız C1, varsayılan | `-Xint`, varsayılan (tek koşu) |
|---|---|---|---|---|
| Sözlük yükleme (`RootLexicon.getDefault()`) | 342 ms (284–372) | 327 ms (318–352) | 285 ms (271–290) | 1805 ms |
| Morfoloji kurulumu (`build()`) | 262 ms (244–307) | 224 ms (220–238) | 242 ms (221–288) | 1901 ms |
| **Toplam açılış** | **613 ms (546–667)** | 562 ms (546–574) | 528 ms (492–578) | 3705 ms |
| Tutulan yığın (yükleme sonrası, GC'den sonra) | **41,6 MB** | 39,9 MB | 41,6 MB | 41,6 MB |
| İlk 120 farklı kelime (gerçekten soğuk) | 38 ms (34–65) | 41 ms (38–55) | 29 ms (26–49) | 124 ms |
| 1000 kelime, sıcak | 17 ms (15–21) | 17 ms (16–23) | 21 ms (19–22) | 396 ms |

JIT ve C1 sütunları 5 koşunun medyanıdır (parantezde en düşük–en yüksek); her satırın medyanı
ayrı alındığı için sözlük + kurulum medyanı toplam medyanına tam eşit değildir.

- **Önbellek açıkken** (`createWithDefaults()` gibi) kullanım sonrası tutulan yığın 44,2 MB;
  yükleme hemen sonrası 44–56 MB arasında oynuyor, çünkü statik önbellek arka planda dolar.
- **En küçük çalışan yığın:** hem varsayılan hem modelsiz kurulumda `-Xmx47m` ile yükleme +
  analiz tamamlanıyor, 46 MB'da `OutOfMemoryError` (1 MB çözünürlük). Bu sınırda açılış GC
  baskısıyla ~1,3 sn'ye çıkıyor.
- **Sözlük:** 67.755 kök. 120 farklı kelimenin hepsi çözümlendi. Kelime listesi küçük; nadir ve
  uzun çekimler az temsil ediliyor.
- **Boyut:** sınıf yolu toplamı **8,3 MB** jar: Guava 2,7 MB, `zemberek-morphology` 2,3 MB,
  protobuf 1,6 MB, Caffeine 0,6 MB, diğerleri ~1 MB. Morfoloji jar'ının içinde sözlük
  `tr/lexicon.bin` 0,5 MB (açık 1,2 MB), belirsizlik giderme modeli 1,4 MB (açık 1,7 MB).
  Model yalnız kelime analizi için gerekmez; çıkarılabilir. R8 küçültmesiyle APK etkisinin
  8,3 MB'ın altında kalması beklenir (tahmin, ölçülmedi).
- **Lisans:** Zemberek-NLP deposundaki `LICENSE` Apache-2.0 (doğrulandı).

**Yorum.** Üç tahminin üçü de masaüstünde beklenenden iyi çıktı: açılış "saniye mertebesi" yerine
~0,6 sn, bellek "onlarca–yüzlerce MB" yerine ~42 MB, boyut "birkaç on MB" yerine 8,3 MB.
Belirsizlik giderme modelini çıkarmak yalnız ~1,7 MB yığın ve ~50 ms kazandırıyor; maliyetin
asıl kaynağı sözlük ve morfotaktik grafik. Klavye açısından:

| Boyut | Ölçülen (masaüstü) | Klavye için anlamı |
|---|---|---|
| Açılış | ~0,6 sn JIT, ~3,7 sn yorumlayıcı | Telefonda (ART, daha yavaş çekirdek) 1–3 sn beklenir (tahmin, cihazda ölçülecek); IME her açılışta bekleyemez, ancak arka planda tembel yükleme olabilir |
| Bellek | ~40–42 MB tutulan yığın, en az 47 MB `-Xmx` | IME sürecine onlarca MB ekler (IME sürecinin kendi belleği henüz ölçülmedi); düşük bellekli cihazda öldürülme riskini artırması beklenir |
| Hız | sıcakken ~17 µs/kelime | Analiz hızı sorun değil; önekten tamamlama üretimi ise doğrudan desteklenmiyor |
| Boyut | 8,3 MB jar (küçültmesiz) | APK'ya eklemek pahalı ama imkânsız değil |
| Bağımlılıklar | Guava, protobuf, Caffeine, ANTLR | Android'de minify/desugar ayarı ve ek bakım yükü |

Sonuç: masaüstü ölçümü varsayılan planla **çelişmiyor**; cihazda kullanım, tahmin edildiğinden
daha az pahalı ama sürekli açık bir klavye için hâlâ ağır. Cihaz ölçümü (ART, referans telefon,
PSS dahil) Faz 3 başında yapılıp bu bölüme eklenecek; karar onunla kesinleşir.

**Öneri: Zemberek'i cihazda değil, masaüstünde ön hesaplama aracı olarak kullan.**

1. `data/` betikleri Zemberek ile korpustaki kökler için sık çekimleri üretir, frekansa göre budar
   ve sıkıştırılmış bir trie/FST'ye (veya `makedict` ile HeliBoard'un ikili sözlük formatına) yazar.
2. Cihazda hafif bir **ek üretici** (Kotlin, `engine/morph`) yalnızca kişisel sözlükteki kökler ve
   sözlük dışı kökler için çalışır: ünlü uyumu (büyük/küçük), ünsüz yumuşaması (`kitap→kitabı`),
   ünsüz benzeşmesi (`-de/-te`), kaynaştırma harfleri, özel isimlerde kesme işareti. Sık ~200 ek
   dizisiyle sınırlı tutulur; kural tabloları Zemberek'in morfotaktiğinden türetilir.
3. Zemberek'in kendisi gerekirse "lazy-load" arka plan iş parçacığında, yalnızca yüksek bellekli
   cihazlarda isteğe bağlı açılabilir (varsayılan kapalı).

Kalan spike adımı (Faz 3 başında): aynı ölçümü referans cihazda (ART) tekrarlamak; açılış,
yığın, işlem belleği (PSS) ve 1000 kelime süresi bu bölüme eklenecek.

## 2. Deasciifier seçenekleri

| Seçenek | Nasıl çalışır | Artı | Eksi |
|---|---|---|---|
| A. Yuret desen tabanlı (Emacs `turkish-mode`; Python/JS/Java portları, Zemberek normalizasyon modülünde de bir portu olduğu biliniyor) | Her belirsiz harf için çevresindeki karakter desenlerine bakan karar listeleri | Sözlük gerektirmez, OOV'da da çalışır, hızlı, literatürde kelime düzeyinde yüksek doğruluk | Bağlamı yalnızca karakter penceresiyle görür; `sik/sık/şık` gibi kelime düzeyi belirsizlikte zayıf |
| B. Sözlük + bağlam | Sözlükteki her kelimenin ascii-katlanmış anahtarı → aday formlar; bağlam n-gram skoruyla seçim | Klavye öneri hattına doğal oturur, sonraki kelime modeliyle aynı veriyi kullanır | Sözlükte olmayan formlarda morfolojiye/A'ya ihtiyaç duyar |
| C. Küçük sinir ağı (karakter düzeyi) | Dizi etiketleme | En yüksek tavan | Model boyutu, gecikme, eğitim verisi; Faz 4 sonrası |

Lisans notu: proje GPL-3.0 olduğu için GPL, MIT ve Apache-2.0 lisanslı portların hepsi uyumlu;
yine de her portun lisansı kaynağından doğrulanıp `data/KAYNAKLAR.md`'ye yazılacak.

**Öneri: B birincil, A yedek.** Sıralayıcı, B'nin adaylarını bağlamla puanlar; sözlükte aday yoksa
A'nın çıktısı aday olarak eklenir. Önemli: AOSP/HeliBoard native motoru aksanlı harfleri temel
harfe yakın sayıyor olabilir (`cok` yazınca `çok` önerisi). Faz 0 baseline'ı bunu ölçecek; Faz 2'nin
hedefi bunun üzerine çıkmak.

## 3. Sonraki kelime modeli (Faz 4 için not)

- İlk adım kuantize, budanmış 3-gram (Kneser-Ney), HeliBoard'un mevcut bigram altyapısına ek olarak.
- Sohbet dili verisi kritik; açık lisanslı Türkçe sohbet verisi kıt. Seçenekler ve lisansları Faz 4
  öncesi `data/KAYNAKLAR.md`'de listelenecek. Kişisel öğrenme bu açığı zamanla kapatır.
