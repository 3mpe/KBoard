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
Belirsizlik giderme modelini çıkarmak yalnız ~1,7 MB yığın ve ~40 ms kurulum süresi kazandırıyor (`build()` medyanı 262 → 224 ms; toplam açılışta koşular arası gürültü içinde); maliyetin
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

## 4. İki dil modeli: ölçüm modeli ve dağıtılan model (2026-11-14)

Aynı korpustan, aynı ayarlarla iki dosya derlenir (`engine/build.gradle.kts`):

| Dosya | Görev | Test sızıntısı süzgeci | Nereye gider |
|---|---|---|---|
| `kboard-tr.kbn` | ölçüm modeli (`buildNgramModel`) | var (`--exclude-jsonl`) | masaüstü ölçümleri ve bütün kalite eşikleri |
| `kboard-tr-ship.kbn` | dağıtılan model (`buildNgramModelShip`) | yok | APK (`assets/kboard/kboard-tr.kbn`) |

**Neden.** Süzgeç, test setleriyle normalize 4-gram paylaşan eğitim cümlelerini atar (510 cümle: cv 450,
sohbet 60). Ölçüm için doğrudur: model test cümlesini görmüşse sayı kaliteyi değil ezberi ölçer. Ama test
setleri özellikle en sık günlük kalıplardan yazıldığı için atılanlar tam da telefonda en çok beklenen
devamlardır: süzgeçli modelde "teşekkür" → "ederim" (korpusta 71 cümle atılıyor), "görüşmek" → "üzere",
"doğum günün" → "kutlu" ilk üçte yoktur. Bu yüzden telefona süzgeçsiz model gider, sayılar süzgeçli
modelden okunur.

**Kurallar.**

- Kalite sayıları (İlk-3, sonraki kelime, KSR, aksan, yazım) yalnız ölçüm modelinden raporlanır; bu
  değişiklikle aynı kalır.
- Dağıtılan modelde yalnız güvenlik denetlenir (`.github/ci/dagitilan-model.sh` ve
  `corpus_false_ac.py --no-exclude`): seed-v0 ve korpusta yanlış otomatik düzeltme, kural-v0 tuzakları,
  de/da/ki tuzakları ve ayrımın otomatik düzeltme olmaması, gecikme p95, boyut bütçesi.
- CI, APK'daki `assets/kboard/kboard-tr.kbn` dosyasının sha256'sının `kboard-tr-ship.kbn` ile aynı,
  `kboard-tr.kbn`'den farklı olduğunu denetler (`ci.yml`, "Debug APK + izin kontrolü").
- **Kirli sayılar.** Dağıtılan APK ile (emülatör veya cihaz) seed-v0, kural-v0, dedaki-v0, kisisel-v0 ya
  da buyuk-harf-v1 `dogal` cümleleri üzerinde ölçülen sonraki kelime / tamamlama / KSR sayıları kalite
  ölçüsü DEĞİLDİR: model bu cümleleri eğitimde görmüştür. Bu koşular yalnız davranış denetimi olarak
  okunur (yanlış düzeltme, büyük harf, kelime değişimi). `dogal` grubu dağıtılan model için artık
  ayrılmış veri sayılmaz.
- Gboard karşılaştırması (#10) ve cihaz üstü kalite sayıları için model derlemesinde hiç kullanılmamış
  yeni bir ayrılmış parti gerekir; o parti hazır olana kadar cihaz üstü kalite sayısı yayımlanmaz.
- Günlük ifade listesi (`eval/ifade/gunluk-ifadeler-v0.tsv`): motor çıktısına bakılmadan ve seed-v0'dan
  alınmadan yazılmış 45 ifade + 3 sabit satır. Eşik değildir, iki modelin ilk üç sonraki kelime önerisini
  yan yana gösterir; yalnız sabit satırlar (teşekkür → ederim, görüşmek → üzere, doğum günün → kutlu)
  dağıtılan modelde zorunludur. İlk ölçüm: ölçüm modeli 36/45, dağıtılan model 39/45, sabit 0/3 → 3/3.

### Taze ölçüm kümeleri (2026-11-15)

seed-v0 ve sohbet korpusu aynı yöntemle yazıldığından, korpus değişikliklerinin kazancı üzerinde hiç ayar
yapılmayan iki kümede de ölçülür (ölçüm modelinden çıkarılırlar; `engine/build.gradle.kts`):

- `taze-sohbet-v0` (110 mesaj, sentetik, ayrı oturumda yazıldı): birincil. Kazanç aynı işaretli olmalı ve
  @0 ile @3'te dev kazancının en az üçte biri kadar olmalı; değilse üsluba aşırı uyum sayılır.
- `taze-dogal-v0` (300 Common Voice cümlesi, okuma metni üslubu): bekçi; @3 ve KSR en çok 1 puan düşebilir.
- `taze-sohbet-tablet-v0` (40 mesaj): yalnız tablet karşılaştırmasında (KBoard / Gboard) kullanılır;
  masaüstünde üzerinde ölçüm ya da ayar yapılmaz. Dağıtılan model bu mesajları da görmemiştir.

Not: `taze-dogal-v0` cümleleri Common Voice'tan çekildiği için dağıtılan modelde vardır; bu küme yalnız
ölçüm modeliyle anlamlıdır. İlk kullanım: `docs/reports/adim-1-korpus.md`.

### Masaüstü ölçümünün kör noktası (#59)

Masaüstü benzetimi yalnız motorun kendi otomatik düzeltme kararını modeller. HeliBoard'un
`Suggest.shouldBeAutoCorrected` kararı (yazılan kelimenin "bu bağlamda bilinmiyor" sayılması) masaüstünde
görünmez; #59 (`mi` → `mı`) bu yüzden yalnız emülatörde yakalandı. Bu yüzden 40 doğru cümle emülatör
koşusu (`gorev=dogru-cumleler`) sürüm kapısı olarak kalır.
