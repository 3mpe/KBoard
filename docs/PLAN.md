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
| JDK | 17+ (Android Studio'nun gömülü JBR'ı yeterli) | `compileOptions` Java 17 |
| Gradle | 9.7.1 (wrapper) | elle kurulum gerekmez |
| Android Gradle Plugin | 9.1.1 | |
| Kotlin | 2.4.0 | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 21 | |
| NDK | **28.0.13004108** | native sözlük motoru (JNI) için zorunlu |
| CMake | gerekmez | `ndkBuild` kullanılıyor |
| Android Studio | Gradle 9 / AGP 9.1 destekleyen güncel kararlı sürüm | |
| Python | 3.10+ | yalnızca `eval/` için, ek paket yok |

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
sonucunu (`autocorrect`: boşluğa basınca metne geçecek kelime) taşır.

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

## 7. Issue haritası

| Faz | Epic | Görevler |
|---|---|---|
| 0 | #1 | 0.1 #7 · 0.2 #8 · 0.3 #12 · 0.4 #9 · 0.5 #10 · 0.6 #11 |
| 1 | #2 | 1.1 #17 · 1.2 #13 · 1.3 #14 · 1.4 #15 · 1.5–1.6 #18 · 1.7 #16 · 1.8 #19 |
| 2 | #3 | 2.1 #20 |
| 3 | #4 | 3.1 #21 |
| 4 | #5 | 4.1 #22 |

Açık sorular: #6.
