# KBoard: Türkçe Odaklı Android Klavye: Proje Planı

> Kaynak: proje brifi (2026-11-12). Bu belge Faz 0 ve Faz 1'i ayrıntılı, Faz 2–4'ü
> çerçeve düzeyinde planlar. Açık sorular: [OPEN_QUESTIONS.md](OPEN_QUESTIONS.md).
> Teknik değerlendirmeler: [EVALUATION.md](EVALUATION.md).

## 0. Kapsam özeti

- **Tek dil: yalnızca Türkçe.** Diğer dillerin sözlükleri ve alt tipleri APK'dan çıkarılır.
  Bu, öneri motorunu "çok dilli birleştirme" yükünden kurtarır ve APK'yı küçültür.
- **Hedef:** Türkçede en iyi otomatik tamamlama, aksan düzeltme ve sonraki kelime / cümle tahmini.
- **Taban:** HeliBoard forku (GPL-3.0). İnternet izni yok (HeliBoard'da da zaten yok,
  `AndroidManifest.xml` doğrulandı; CI'da bu kontrol edilecek).
- **Kapsam dışı:** glide, ses, GIF, çeviri, iOS.

## 1. Depo yapısı ve modül sınırları

```
KBoard/
├── app/                  HeliBoard (fork) — dokunuş minimum, değişiklikler "KBOARD:" yorumuyla işaretli
├── engine/               :engine — saf Kotlin/JVM modülü, Android bağımlılığı YOK
│   ├── api/              SuggestionEngine, CandidateSource, Ranker arayüzleri
│   ├── deasciify/        Faz 2
│   ├── morph/            Faz 3 (ek üretimi, kişisel köklerin çekimleri)
│   └── lm/               Faz 4 (n-gram)
├── eval/                 Faz 0 ölçüm aracı (Python, yalnızca standart kütüphane)
│   ├── kboard_eval/      test seti, iz (trace), metrik, CLI
│   ├── testset/          repoya giren, lisansı açık test cümleleri
│   └── private/          kişisel test verisi — .gitignore, cihaz/bilgisayardan çıkmaz
├── data/                 korpus/sözlük üretim betikleri + KAYNAKLAR.md (lisans listesi)
└── docs/                 plan, açık sorular, değerlendirmeler, ölçüm raporları
```

**Sınır kuralları**

1. `engine/` Android'e bağımlı olmaz; masaüstünde JUnit ile ve `eval/` aracından
   (JSON-satır alt süreç protokolüyle) çalıştırılabilir. Böylece öneri kalitesi cihaza
   APK yüklemeden ölçülür.
2. `app/` içindeki HeliBoard koduna dokunuşlar tek bir **adaptör** katmanında toplanır
   (`app/src/main/java/helium314/keyboard/kboard/`). Upstream dosyalarında yalnızca
   birkaç satırlık "bağlantı" değişikliği olur ve hepsi `// KBOARD:` ile işaretlenir.
   Bu, upstream merge'lerini kolaylaştırır.
3. Fork stratejisi (önerilen, onay bekliyor: OQ-3): KBoard deposunun kökü HeliBoard
   ağacı olur (`git merge --allow-unrelated-histories upstream/main`), bizim dizinlerimiz
   (`engine/`, `eval/`, `data/`, `docs/`) HeliBoard'da bulunmayan isimlerdir; çakışma yok.
   Upstream takibi: `upstream` remote, ayda bir merge, merge sonrası ölçüm.

## 2. Geliştirme ortamı (HeliBoard `main`, 2026-11-07 itibarıyla doğrulandı)

| Bileşen | Sürüm | Not |
|---|---|---|
| JDK | 21 (Android Studio'nun gömülü JBR'ı yeterli) | `compileOptions` Java 17 hedefliyor; Robolectric testleri (SDK 36) Java 21 istiyor |
| Gradle | 9.7.1 (wrapper) | elle kurulum gerekmez |
| Android Gradle Plugin | 9.1.1 | |
| Kotlin | 2.4.0 | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 21 | |
| NDK | **28.0.13004108** | native sözlük motoru (JNI) için zorunlu |
| CMake | gerekmez | `ndkBuild` kullanılıyor |
| Android Studio | Gradle 9 / AGP 9.1 destekleyen güncel kararlı sürüm | |
| Python | 3.10+ (`python3` PATH'te) | `eval/` ve APK derlemesi (aksan dizini `data/tools/dict_dump.py` ile üretilir); ek paket yok |

Derleme yeri (OQ-7): önerilen yol GitHub Actions'ta debug APK üretmek; kullanıcının
Windows makinesinde yalnızca Android Studio + cihaz testi.

## 3. HeliBoard öneri hattı ve bağlantı noktaları

İncelenen kod (HeliBoard `3e91b4f`):

```
LatinIME → InputLogic → Suggest.getSuggestedWords()                  suggestions/Suggest.kt
  ├─ getSuggestedWordsForNonBatchInput()   yazarken
  ├─ getNextWordSuggestions()              boşluktan sonra (boş ComposedData + NgramContext)
  └─ DictionaryFacilitatorImpl.getSuggestionResults()                 DictionaryFacilitatorImpl.kt
        └─ getSuggestions(dictGroup): ALL_DICTIONARY_TYPES üzerinde döngü
              main (ReadOnlyBinaryDictionary, native) · contacts · apps · history · user
        → SuggestionResults (skora göre sıralı küme)
  └─ Suggest.shouldBeAutoCorrected()       otomatik düzeltme kararı
```

**Bağlantı noktası A (birincil): yeni `Dictionary` alt sınıfı.**
`KBoardDictionary : Dictionary` (yeni tür, ör. `TYPE_KBOARD = "kboard"`), `engine/`
modülündeki `SuggestionEngine`'e delege eder. Değişiklik: `DictionaryFacilitator.ALL_DICTIONARY_TYPES`
dizisine bir giriş ve `DictionaryFacilitatorImpl.createSubDict`/`DictionaryGroup` oluşturmada
bir satır. Hem yazarken hem sonraki kelime çağrıları aynı `getSuggestions(composedData, ngramContext, …)`
arayüzünden geçtiği için deasciifier, morfoloji ve n-gram tek noktadan bağlanır.

**Bağlantı noktası B (ikincil): sıralayıcı.** Native ana sözlük ile bizim adaylarımızın skorları
aynı ölçekte değil. Gerekirse `Suggest.getSuggestedWordsForNonBatchInput` içinde
`SuggestionResults` oluştuktan sonra `KBoardRanker.rerank()` çağrısı (tek satır) eklenir.
Otomatik düzeltme için `shouldBeAutoCorrected` girdisi de buradan etkilenir.

**Bağlantı noktası C: kişisel sözlük.** `UserBinaryDictionary` (Android kullanıcı sözlüğü) değişmeden
kalır; `KBoardDictionary` kullanıcı kelimelerini okuyup morfoloji ile çekimlerini üretir
("Kadıköy" → "Kadıköy'de", "Kadıköy'den").

Not: HeliBoard'un `AI_USAGE.md` dosyası upstream'e yapay zekâ üretimi PR/issue açılmasını
yasaklıyor. Bu fork'taki değişiklikler upstream'e gönderilmez.

## 4. Faz 0: Ölçüm altyapısı (ayrıntılı)

### 4.1 Test seti formatı (`eval/testset/*.jsonl`)

Her satır bir vaka:

```json
{"id": "sohbet-0001", "text": "Yarın Kadıköy'de buluşalım mı?",
 "input": "Yarin Kadikoy'de bulusalim mi?",
 "category": "sohbet", "tags": ["deasciify", "ozel_isim", "ek"],
 "user_words": ["Kadıköy"], "source": "elle", "license": "CC0-1.0"}
```

| Alan | Zorunlu | Anlam |
|---|---|---|
| `id` | evet | benzersiz kimlik |
| `text` | evet | kullanıcının **niyet ettiği** doğru metin (hedef) |
| `input` | hayır | kullanıcının gerçekte yazdığı metin (aksansız / hatalı). Yoksa `text` ile aynıdır |
| `category` | evet | `sohbet`, `resmi`, `ozel_isim`, `kullanici_sozlugu`, `dogru_yazim` … |
| `tags` | hayır | metrik kırılımı için etiketler |
| `user_words` | hayır | vakadan önce kişisel sözlüğe eklenecek kelimeler |
| `source`, `license` | evet | veri kökeni (lisans takibi için) |

Kurallar: `input` ve `text` aynı sayıda kelimeye bölünmeli (kelime hizalaması); kesme işaretli
ekler tek kelime sayılır (`Kadıköy'de`). Doğrulama `kboard-eval validate` ile yapılır.
Kişisel/gerçek mesajlardan derlenen vakalar yalnızca `eval/private/` altında tutulur.

### 4.2 İz (trace) formatı: ölçüm ile klavyeyi ayırır

Her klavye (Gboard, saf HeliBoard, KBoard, masaüstü motoru) aynı formatta iz üretir;
puanlayıcı yalnızca izi okur. Böylece kara kutu (cihaz) ve beyaz kutu (masaüstü) ölçümler
aynı metriklerle karşılaştırılır.

```json
{"case_id": "sohbet-0001", "word_index": 1, "typed": "Kad",
 "suggestions": ["Kadıköy", "Kadın", "Kadar"], "autocorrect": null, "latency_ms": 7.4}
```

`typed` = o kelimenin o ana kadar yazılmış öneki (`input` metninden), `""` = sonraki kelime
tahmini anı. Kelimenin tamamı + boşluk anı için `"commit": true` satırı otomatik düzeltme
sonucunu (`autocorrect`: boşluğa basınca metne geçecek kelime) taşır. Commit satırının `suggestions` alanı boşluğa basılmadan **hemen önceki**
öneri çubuğudur (boşluktan sonra çubuk sonraki kelime tahminine geçer; cihaz sürücüsü onu bu ana
yazmamalı).

### 4.3 Metrikler

| Metrik | Tanım |
|---|---|
| İlk-3 isabet @k | `k` karakter yazıldıktan sonra hedef ilk 3 öneride mi (k=0 sonraki kelime, 1, 2, 3). k>0 için en az bir karakter yazılacak kalmalı (yazılanı göstermek bedava isabet olmasın); k=0'da cümlenin ilk kelimesi sayılmaz |
| Sonraki kelime isabeti | k=0 için ilk-1 / ilk-3 |
| Tuş tasarrufu (KSR) | Kullanıcı hedefi ilk 3'te gördüğü an dokunarak seçer (1 tuş, boşluk dahil). `KSR = 1 − kullanılan / (karakter + boşluk)`. Hatasız seçim varsayılır; yanlış commit'in düzeltme maliyeti sayılmaz |
| Aksan düzeltme doğruluğu | `input ≠ text` olan ve farkı yalnızca Türkçe karakterler olan kelimelerde commit sonucu = hedef |
| Yanlış otomatik düzeltme | `input = text` (doğru yazılmış) kelimelerde commit sonucu ≠ yazılan |
| Yazım hatası düzeltme | `input ≠ text` olan ve farkı yalnızca Türkçe karakterler **olmayan** kelimelerde (harf eksik/fazla/yanlış/yer değişmiş; aksanla birlikte de olabilir) commit sonucu = hedef. Ek olarak hedefin commit anında ilk 3 öneride olma oranı. Yanlış otomatik düzeltmenin karşı ağırlığıdır: biri artarken diğeri de artıyorsa düzeltme fazla saldırgandır |
| Gecikme | izdeki `latency_ms` için p50 / p95 / max |

Karşılaştırma büyük/küçük harf duyarsızdır ve Türkçe kurallarla yapılır (`I→ı`, `İ→i`).
Kırılım: kategori ve etikete göre.

### 4.4 Ölçüm aracı (`eval/`)

- Python 3.10+, **yalnızca standart kütüphane** (bağımlılık yok).
- Komutlar: `validate` (test seti), `simulate` (masaüstü öngörücüyle iz üret),
  `score` (izden rapor: JSON + Markdown).
- Öngörücü arayüzü: `predict(context_words, prefix) → (suggestions, autocorrect)`.
  Dahili `freq` öngörücüsü (kelime listesi + önek eşleşmesi) boru hattını sınamak içindir.
  `subprocess` öngörücüsü JSON-satır protokolüyle `engine/` CLI'ını çağırır (Faz 2'de).
- Cihaz sürücüsü (`adb`): her karakteri, klavye başına tutulan bir tuş koordinat haritasıyla
  `adb shell input tap x y` olarak ekrandaki tuşa dokunarak yazar (`input text` donanım tuş olayı
  gönderir, yumuşak klavyenin dokunma yolunu atlar ve ç/ş/ğ/ı/ö/ü gönderemez). Her dokunuştan sonra
  öneri çubuğu `uiautomator dump` ile okunur ve iz üretilir. Gboard ve saf HeliBoard baseline'ı bu
  yolla, aynı koşullarda alınır (OQ-1). Öneri çubuğu okunamazsa OQ-1'deki yedek yollar geçerli.

### 4.5 Faz 0 adımları ve bitiş kriterleri

| # | Adım | Bitiş kriteri |
|---|---|---|
| 0.1 | Test seti formatı + ölçüm aracı iskeleti (validate / simulate / score, birim testler) | `python -m unittest` yeşil; örnek set için rapor üretiliyor |
| 0.2 | Tohum test seti v0: ≥300 cümle (sohbet ağırlıklı), ≥100 aksansız vaka, ≥50 kişisel sözlük vakası, ≥100 "doğru yazım" vakası | `validate` hatasız; kategori dağılımı raporlandı |
| 0.3 | Kullanıcının HeliBoard test bulgularını vakaya dönüştürme | Her bulgu ≥1 vakayla temsil ediliyor |
| 0.4 | `adb` cihaz sürücüsü (öneri çubuğunu okuma) | Aynı vaka 3 koşuda aynı izi veriyor (deterministik) |
| 0.5 | Gboard ve saf HeliBoard baseline ölçümü | `docs/reports/baseline-v0.md` yayınlandı |
| 0.5b | Yazım hatası düzeltme metriği (aksan dışı farklar: `input` ≠ `text`), yanlış otomatik düzeltme metriğinin karşı ağırlığı | Metrik raporda; test setinde ≥50 yazım hatası vakası |
| 0.6 | Sayısal hedeflerin belirlenmesi | Faz 2–4 bitiş eşikleri bu belgeye işlendi |

## 5. Faz 1: Fork + temel (ayrıntılı)

| # | Adım | Bitiş kriteri |
|---|---|---|
| 1.1 | HeliBoard'u depoya alma (upstream remote, unrelated-histories merge) **(onay gerekli)** | Depo kökünde HeliBoard ağacı + bizim dizinler; upstream commit'i `docs/UPSTREAM.md`'de kayıtlı |
| 1.2 | Kimlik: `applicationId`, uygulama adı, ikon (özgün), sürüm adı | Kurulu HeliBoard ile yan yana kurulabiliyor |
| 1.3 | Tek dil (`main_tr.dict` HeliBoard'da `app/src/main/assets/dicts/` altında gömülü, doğrulandı): diğer sözlükleri çıkar, varsayılan alt tip `tr` (Q) | APK'da yalnızca TR sözlüğü; ilk açılışta Türkçe düzen |
| 1.4 | Varsayılan büyük tuşlar + tuş kenarlıkları açık (`Defaults.kt`: `PREF_KEYBOARD_HEIGHT_SCALE`, `PREF_THEME_KEY_BORDERS`) | Kullanıcı tuş hissini onayladı |
| 1.5 | İnternet izni yokluğunun CI kontrolü (birleştirilmiş manifest) | CI, `INTERNET` izni eklenirse kırılıyor |
| 1.6 | GitHub Actions: debug APK + birim testler | Her PR'da indirilebilir APK |
| 1.7 | `:engine` modül iskeleti + `KBoardDictionary` adaptörü (boş aday döndürür) | APK davranışı değişmeden çalışıyor; adaptör çağrıldığı log/testle doğrulandı |
| 1.8 | Faz 1 ölçümü | Skorlar saf HeliBoard ile aynı (gerileme yok) |

## 6. Faz 2–4 (çerçeve)

- **Faz 2 – Deasciifier:** ascii-katlanmış anahtar → aday formlar dizini (`main_tr.dict` + morfoloji
  çıktılarından), bağlam skoru (n-gram), Yuret desen tabanlı yöntem yedek/OOV için. Bitiş: aksan
  düzeltme doğruluğu baseline'ı belirgin aşar, yanlış otomatik düzeltme artmaz.
- **Faz 3 – Morfoloji:** Zemberek masaüstünde **ön hesaplama** için; cihazda hafif ek üretici
  (ünlü uyumu, ünsüz yumuşaması, kesme işareti) + kişisel köklerin çekimleri. Bitiş: ilk-3 ve KSR'de Gboard'u geçer.
- **Faz 4 – Sonraki kelime + kişisel öğrenme:** kuantize n-gram (açık lisanslı korpus), cihazda
  öğrenme; gerekirse küçük TFLite/ONNX LM. Bitiş: sonraki kelime isabeti Gboard'u geçer, p95 gecikme < 50 ms.

Her faz sonunda ölçüm yapılır, devam kararı skora göre verilir.

### 6.1 Sayısal bitiş eşikleri (adım 0.6, #11)

> **TASLAK: depo sahibinin onayı bekleniyor.** Aşağıdaki sayılar yeni karar değildir; depoda bugün
> uygulanan CI eşiklerinin ve raporlarda ölçülen değerlerin dökümüdür. Onaylanana kadar bağlayıcı
> bitiş kriteri sayılmaz. Gboard'a göre hedefler #10 (baseline ölçümü) bitmeden sayıya bağlanamaz.

Ölçüm seti aksi yazılmadıkça `seed-v0` (327 vaka; 289 aksan vakası, 904 doğru yazılmış kelime), masaüstü
simülasyonu. Tanımlar: `docs/EVALUATION.md`.

**(a) Bugün CI'da uygulanan eşikler** ("Motor ölçüm eşiği" işi, `.github/workflows/ci.yml:30`; eşik
aşılırsa PR kırılır)

| Faz | Metrik | Eşik | Uygulandığı yer |
|---|---|---|---|
| 2 | Aksan düzeltme doğruluğu | ≥ %88 | `.github/ci/eval-gate.py:17` |
| 2 | Yanlış otomatik düzeltme (seed-v0) | ≤ %0,3 | `.github/ci/eval-gate.py:18` |
| 2 | Yanlış otomatik düzeltme (korpus, bağlamsız ve bağlamlı) | ≤ %0,3 | `.github/workflows/ci.yml:90`, `data/tools/corpus_false_ac.py:210-215` |
| 2 | Yazım kurallarının korpusa eklediği yanlış düzeltme | ≤ +0,01 puan | `.github/workflows/ci.yml:91` |
| 3 | Kişisel sözlük kategorisi İlk-3 @3 (çekim üreticisi) | ≥ %25 | `.github/ci/eval-gate.py:23` |
| 4 | Sonraki kelime İlk-3 @0 | ≥ %18,5 | `.github/ci/eval-gate.py:19` |
| 4 | İlk-3 @1 | ≥ %28,5 | `.github/ci/eval-gate.py:20` |
| 4 | İlk-3 @3 | ≥ %50 | `.github/ci/eval-gate.py:21` |
| 4 | KSR (tuş tasarrufu) | ≥ %37,5 | `.github/ci/eval-gate.py:22` |
| 4 | Kişisel öğrenme: tekrar İlk-3 @0 ≥ %45 ve farkı ≥ +20 puan; @1 ≥ %60; KSR farkı ≥ +8 puan; iki kullanımdan sonra yeni kelime ≥ %80; seed-v0'da kötüleşme ≤ 0,5 puan | (soldaki) | `.github/ci/personal-gate.py:49-53,62,78` |

Faz dışı ama aynı işte uygulanan yazım kuralı eşikleri: `kural-v0` tuzaklarda 0 otomatik düzeltme,
kesinlik ≥ %99, liste duyarlılığı ≥ %95, soru eki duyarlılığı ≥ %85 (`.github/ci/kural-gate.py:37-40`);
de/da/ki ayrımı ilk 3'te ≥ %60, ilk yuvada ≥ %40, tuzakta ilk yuvada ≤ %10, otomatik düzeltme 0
(`.github/ci/dedaki-gate.py:96-99`); korpusta ayrık öneri ilk yuvada ≤ %5 (`.github/workflows/ci.yml:82`).

**Gecikme p95 CI'da uygulanmıyor.** Raporlarda kullanılan hedef ≤ 1,5 ms'dir (masaüstü, uçtan uca;
`docs/reports/faz4-ngram.md:45`, `docs/reports/faz-kural-b.md:31`, `docs/reports/faz7-kisisel.md:172`).
§6'daki "p95 < 50 ms" cihaz hedefidir; telefonda henüz ölçülmedi (`docs/reports/faz1.md:54`,
`docs/reports/faz4-ngram.md:66`).

**(b) Bugünkü ölçülen değerler** (seed-v0 tüm set, raporlardan)

| Metrik | Ölçülen | Rapor |
|---|---|---|
| Aksan düzeltme doğruluğu | %90,3 (test yarısı %89,1) | `docs/reports/faz-aksan-baglam.md:40` |
| Yanlış otomatik düzeltme (seed-v0) | %0,1 (904 kelime) | `docs/reports/faz-aksan-baglam.md:41` |
| Yanlış otomatik düzeltme (korpus, 21 687 sözcük) | %0,04–%0,06 | `docs/reports/faz-aksan-baglam.md:57-60`, `docs/reports/faz7-kisisel.md:163-164` |
| İlk-3 @0 / @1 / @3 | %20,7 / %30,6 / %52,3 | `docs/reports/faz-aksan-baglam.md:42` |
| KSR | %39,9 | `docs/reports/faz-aksan-baglam.md:43` |
| Gecikme p95 (masaüstü) | 1,06 ms; yükte 1,14–1,55 ms | `docs/reports/faz-aksan-baglam.md:44` |
| Gecikme p95, dolu kişisel depo | 1,32–1,54 ms (ortanca 1,36) | `docs/reports/faz7-kisisel.md:172` |

Not: korpus yanlış düzeltmesi için `docs/reports/faz-kural.md:32` %0,10 / %0,11 veriyor (yazım kuralları
açıkken, ham); raporlar arasındaki bu fark onay öncesi tek bir güncel koşuyla netleştirilmeli.

**(c) Gboard'a göre hedefler: #10 bekleniyor**

| Faz | Hedef (§6) | Sayı |
|---|---|---|
| 2 | Aksan düzeltme doğruluğu baseline'ı belirgin aşar, yanlış otomatik düzeltme artmaz | #10 bekleniyor |
| 3 | İlk-3 ve KSR'de Gboard'u geçer | #10 bekleniyor |
| 4 | Sonraki kelime isabeti (İlk-3 @0) Gboard'u geçer | #10 bekleniyor |

`docs/reports/baseline-v0.md` (adım 0.5) henüz yok; Gboard ve saf HeliBoard değerleri ölçülmeden bu
satırlara sayı yazılmaz. Baseline yayımlanınca her satıra "Gboard değeri + pay" biçiminde eşik önerilir
ve (a)'daki mutlak eşiklerle birlikte onaya sunulur.

## 7. Issue haritası

| Faz | Epic | Görevler |
|---|---|---|
| 0 | #1 | 0.1 #7 · 0.2 #8 · 0.3 #12 · 0.4 #9 · 0.5 #10 · 0.6 #11 |
| 1 | #2 | 1.1 #17 · 1.2 #13 · 1.3 #14 · 1.4 #15 · 1.5–1.6 #18 · 1.7 #16 · 1.8 #19 |
| 2 | #3 | 2.1 #20 |
| 3 | #4 | 3.1 #21 |
| 4 | #5 | 4.1 #22 |

Açık sorular: #6.
