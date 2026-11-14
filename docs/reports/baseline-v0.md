# Ölçüm tabanı v0 (kısmi)

Durum: **kısmi.** Emülatör yarısı bir kez koşuldu (koşu 43600969904, commit `a0e0c65d`, 2026-11-14,
12 vaka); sayılar "Sonuçlar" bölümündedir. Gboard yarısı gerçek cihaz bekliyor (aşağıda). İlgili işler: #10 (taban ölçümünün bu yarısı), #2, #3; #19 için
kullanılıp kullanılmayacağı açık.

## Ne ölçülüyor

Aynı debug APK, aynı `main_tr.dict`, aynı klavye düzeni ve aynı ayarlarla, seed-v0'ın sabit bir alt
kümesi emülatörde gerçek tuş dokunuşlarıyla iki kez yazılır:

| Geçiş | Raporlardaki adı | Tanım |
|---|---|---|
| 1 | **KBoard (motor açık)** | KBoard debug derlemesi, varsayılan hâl |
| 2 | **HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)** | Aynı APK; KBoard öneri motoru debug anahtarıyla kapalı. Öneriler ve otomatik düzeltme yalnız HeliBoard'un kendi sözlük hattından gelir |

İki geçişte de: otomatik büyük harf kapalı (`auto_cap=false`), öğrenme kapalı
(`use_personalized_dicts=false`; HeliBoard'un kullanıcı geçmişi sözlüğü de KBoard'un kişisel modeli de
devre dışı), otomatik düzeltme varsayılan (açık), Türkçe Q, kişisel sözlük boş.

### Adlandırma kuralı

İkinci geçiş raporlarda, tablolarda ve PR açıklamalarında yalnız **"HeliBoard eşdeğeri (KBoard
derlemesi, motor kapalı)"** diye anılır. Stok HeliBoard değildir; "saf HeliBoard" denmez.
`docs/PLAN.md` §4.2 ve adım 0.5'teki "saf HeliBoard" ifadesi bu ölçümü karşılamaz: stok HeliBoard
APK'sı ölçülmedi. Stok HeliBoard ile arasındaki farklar aşağıda tek tek sayılmıştır.

## Motor nasıl kapatılıyor

Mevcut kullanıcı ayarları motoru tümüyle kapatmaya yetmiyor:

| Parça | Mevcut ayarla kapanır mı |
|---|---|
| Aksan düzeltici, yazım kuralları (liste, bitişik soru eki), ayrık bağlaç (de/da/ki), yazım hatası ve ağır kayma (`TypoSource`), ek zinciri | Evet: "Türkçe aksan düzeltici" (`kboard_deasciify=false`) hepsini birlikte kaldırır |
| Kişisel öğrenme (`PersonalSource`, `KBoardPersonal.learn`) | Evet: kişiselleştirme (`use_personalized_dicts=false`) |
| Dil modeli (`NgramSource`: sonraki kelime tahmini, tamamlama) | **Hayır.** Ayardan bağımsız, hep yüklenir |
| Kişisel sözlük kelimelerinin çekimleri (`UserWordSuffixSource`) | **Hayır.** Hep açık (kişisel sözlük boşken aday üretmez) |
| `mergeAutoCorrection` içindeki büyük harf koruması (HeliBoard'un "yoruldum" → "Yoruldum" düzeltmesini dil modeline bakarak engeller) ve işaretsiz KBoard önerisinin düzeltmeye gitmesini engelleyen koruma | **Hayır.** Dil modeli yüklüyken çalışır |
| Öneri yasağı listesi (yalnız motorun adaylarını süzer) | Motor adayı yoksa etkisiz |

Bu yüzden yalnız debug derlemesinde çalışan tek bir anahtar eklendi:

- Tercih: `kboard_debug_engine_off` (boolean). Adı ve onu okuyan kod yalnız debug kaynak kümesindedir
  (`app/src/debug/java/helium314/keyboard/kboard/TestFieldActivity.kt`). Ayarlar ekranında görünmez;
  CI `set_prefs` ile yazar (`.github/ci/emu-lib.sh`).
- `TestFieldActivity` her açılışta tercihi okur ve `KBoardSuggestions.setEngineOffForDebug(...)` çağırır.
  Klavye aynı süreçte çalışır. Çağrı `BuildConfig.DEBUG` ile korunur: release'te bu sabit `false`tur,
  çağrı hiçbir şey değiştirmez; release'te çağıran kod da yoktur.
- Anahtar açıkken `KBoardSuggestions` içindeki dört bağlantı hiçbir şey yapmaz: `getSuggestions`
  (aday eklemez), `mergeAutoCorrection` (HeliBoard'un kararını aynen döndürür), `replaceMisspellings`
  (HeliBoard'un önerilerine dokunmaz), `noteRevert` (geri alma kaydetmez). `isRevertiblePick` KBoard
  kaynaklı öneri kalmadığı için kendiliğinden hep `false` döner.
- Kanıt: emülatör betiği her geçişte logcat'ten `motor anahtarı: açık|kapalı` satırını ve motorun
  debug `düzeltme:` satırlarını sayar. `düzeltme:` satırı varsayılan kapalıdır; betik iki geçişten de
  önce `adb shell setprop log.tag.KBoardSuggestions DEBUG` ile yalnız emülatörde açar. Motor kapalı geçişte `düzeltme:` satırı sıfır olmalı, açık
  geçişte en az bir olmalı; değilse geçiş raporda **GEÇERSİZ** diye işaretlenir ve iş kırmızı biter.
- Release denetimi: `.github/ci/debug-anahtar-kontrol.sh` release APK'nın dex'inde
  `kboard_debug_engine_off` ve `TestFieldActivity` adlarının geçmediğini denetler (`cihaz-olcum.yml`,
  `gorev=surum-duman`) ve imzalı sürüm iş akışında (`release.yml`) izin listesinden hemen sonra çalışır.
  `ci.yml` aynı betiği debug APK'da çalıştırıp başarısız olmasını bekler (olumsuz kontrol).

## Stok HeliBoard'dan kalan farklar (motor kapalıyken)

Karşılaştırma tabanı: `docs/UPSTREAM.md`'deki upstream commit `3e91b4f` (4.3-beta1 + 42 commit);
liste `git diff 3e91b4f HEAD -- app/` çıktısından ve `// KBOARD` işaretlerinden çıkarıldı.

Aynı kalanlar: `app/src/main/assets/dicts/main_tr.dict` upstream commit'indeki dosyayla bayt bayt
aynıdır (aynı git blob'u); `app/src/main/jni/` (yerel öneri kitaplığı kaynağı), `assets/layouts/` ve
`assets/locale_key_texts/` değişmedi. Yayımlanmış HeliBoard APK'sının hangi sözlükle geldiği bu
belgede doğrulanmadı.

### Öneri ve düzeltme sonucunu etkileyebilecek farklar

| Fark | Yer | Neden önemli olabilir |
|---|---|---|
| Klavye yüksekliği 1,15 (stok 1,0) | `Defaults.PREF_KEYBOARD_HEIGHT_SCALE` | Tuş geometrisi HeliBoard'un yakınlık (komşu tuş) modeline girer; dokunuş koordinatları da farklıdır |
| Sıralar arası boşluk %4,6 (stok %3,527) | `res/values/config.xml` `config_key_vertical_gap_holo` | Aynı |
| Alt sırada emoji tuşu açık (stok kapalı) | `Defaults.PREF_SHOW_EMOJI_KEY` | Alt sıra tuş genişlikleri değişir |
| Varsayılan alt tip sistem dilinden bağımsız Türkçe Q | `SubtypeSettings.kt` | Stok HeliBoard sistem diline göre alt tip seçer; İngilizce emülatörde stok ölçüm ayrıca Türkçe'ye geçirilmeliydi |
| Yalnız Türkçe sözlük paketli (17 sözlük çıkarıldı) | `assets/dicts/` | Tek dilli ölçümde etkisiz olması beklenir |
| Motor kaynakları yine yüklenir (aksan dizini, dil modeli, ek zinciri, yasak listesi) | `KBoardEngineLoader.init` | Çıktıyı etkilemez; bellek ve açılış süresi stoktan farklıdır, gecikme karşılaştırması yapılamaz |
| İki kelimelik metnin kullanıcı geçmişinden kelime kelime geri alınması | `DictionaryFacilitatorImpl.unlearnFromUserHistory` | Öğrenme kapalıyken çalışmaz |
| `KBoardPersonal.learn/unlearn/clear` çağrıları | `DictionaryFacilitatorImpl` | Anahtar bunları durdurmaz; öğrenme kapalıyken çağrılmaz, açık olsa da motor kapalıyken çıktıya yansımaz |
| Kişisel sözlük kelimelerinin motora verilmesi | `UserBinaryDictionary.java` | Yalnız motorun durumunu günceller |
| Debug derlemesi (küçültme açık, `dontoptimize.pro`), sürüm 4.3-beta1 + 42 commit | `app/build.gradle.kts` | Yayımlanmış bir HeliBoard sürümü değildir |

### Görünüm ve davranış farkları (öneri sonucunu etkilemesi beklenmeyen)

- Tema: `PREF_THEME_STYLE` yuvarlak (stok Material), tuş kenarlıkları açık (stok kapalı), KBoard'un
  açık/koyu renkleri (`KeyboardTheme.kt`, `Colors.kt`), hap biçimli `?123` ve eylem tuşu
  (`KeyboardView.java`, `themes-rounded-base-border.xml`, `attrs.xml`).
- Araç çubuğu: klavye açılınca görünür, yazmaya başlayınca gizlenir (`PREF_AUTO_SHOW_TOOLBAR` ve
  `PREF_AUTO_HIDE_TOOLBAR` true; stokta ikisi de false).
- Mikrofon tuşu varsayılan olarak sabit, öneri şeridinin sağında (`ToolbarUtils.kt`). Öneri şeridi bu
  yüzden stoktan dardır; tuş haritası (`eval/keymaps/kboard-pixel6-emulator.json`) şerit sınırını buna
  göre verir. Mikrofona ilk dokunuş yalnız bildirim gösterir (`KBoardVoice`, `LatinIME.java`).
- Tuş üstü ipuçları: yalnız üst sırada rakam (`KBOARD_POPUP_KEYS_HINT_ORDER`).
- Silme: basılı tutunca kelime kelime siler (`PREF_DELETE_LONG_PRESS = 2`; `PointerTracker.java`,
  `KeyboardActionListenerImpl.kt`, `RichInputConnection.java`, `StringUtils.wordDeleteLength`,
  `InputLogic.java`). Sürücü alanı donanım `DEL` tuş olayıyla temizler, bu yola girmez.
- Ayarlar: "Türkçe aksan düzeltici" anahtarı, "Öğrenilenleri sil", dışa/içe aktarma, silme seçeneği
  (`TextCorrectionScreen.kt`, `AdvancedScreen.kt`, `MainSettingsScreen.kt`, `KBoardTransfer*`).
- Paket adı `io.github.kboard.tr(.debug)`, simge, metinler, yedekleme kuralları (`AndroidManifest.xml`,
  `res/xml/kboard_*`), `DebugFlags.kt`'deki test düzeltmesi.

### Ölçüm ayarlarının varsayılandan farkı (iki geçişte de)

`auto_cap=false`, `use_personalized_dicts=false`. İkisi de stok HeliBoard varsayılanı değildir
(stokta ikisi de açık). "Türkçe aksan düzeltici" ayarı varsayılanında (açık) bırakılır; motor kapalı
geçişte anahtar onu da etkisiz kılar.

## Yöntem

- İş akışı: `.github/workflows/cihaz-olcum.yml`, `gorev=taban`; tek emülatör işi (matris yok),
  API 34, `pixel_6` profili. Betik: `.github/ci/taban.sh`, yardımcı: `.github/ci/taban.py`.
- Alt küme kuralı: `eval/testset/seed-v0.jsonl` içinden kimlik öneki `aksan-`, `dogru-`, `yazim-`
  olan üç grup; `user_words` içermeyen ve tuş haritasıyla yazılabilen vakalar; her grupta
  `crc32(id)` sırasıyla ilk 4 vaka. Toplam 12 vaka, 44 kelime, 319 dokunuşluk metin. Kişisel sözlük
  (`ksoz-`) vakaları dışarıdadır: sürücü cihaza kişisel sözlük kurmuyor.
- Boyut gerekçesi: genel sürücü her harften sonra ekranı okur; önceki `dogru-cumleler` koşularında
  vaka başına yaklaşık 80 sn. 12 vaka × 2 geçiş × 80 sn ≈ 32 dk; derleme, emülatör açılışı ve iki
  ayar turu için yaklaşık 15 dk varsayımıyla iş yaklaşık 47 dk diye tahmin edilmişti. İlk koşuda
  (43595663455) ilk vaka ısınmayla yaklaşık 4 dk, sonrakiler yaklaşık 2,3 dk sürdü ve 1260 sn'de 12 vakanın
  9'u yazılabildi; süre sınırı bu yüzden geçiş başına 1560 sn yapıldı (iş yaklaşık 65 dk), dolunca yeni vakaya başlanmaz.
- Sürücü: `kboard_eval.adb_driver.DeviceDriver` (`python -m kboard_eval drive` ile aynı kod). Fark:
  iz her vakadan sonra diske yazılır ve hata veren vaka atlanır. İkinci geçiş yalnız ilkinde eksiksiz
  yazılan vakaları yazar; puanlar yalnız iki geçişte de eksiksiz vakalardan hesaplanır.
- Puanlama: `python -m kboard_eval score` (kategori ve etiket kırılımlı tablo), her geçiş için ayrı.
  Ek olarak boşlukta metne geçen kelimenin iki geçişte ayrıştığı yerlerin listesi.
- Çıktılar (`cihaz-olcum` artefaktı, `taban/`): `vakalar.jsonl`, `acik.jsonl`, `kapali.jsonl` (izler),
  `ortak.jsonl`, `puan-acik.{md,json}`, `puan-kapali.{md,json}`, `fark.md`, `rapor.md`,
  `logcat-acik.txt`, `logcat-kapali.txt`, ekran görüntüleri.

### Sınırlar

- Örneklem çok küçük (12 vaka). Etiket kırılımlarının çoğunda n tek hanelidir; bu koşu yön gösterir,
  eşik belirlemeye yetmez. #10 ya da #11 için bu tabandan eşik türetilmeden önce daha büyük bir koşu
  gerekir (sonraki adım: `buyuk-harf` görevi gibi birden çok emülatöre bölünmüş koşu).
- Geçiş sırası sabittir: önce motor açık, sonra motor kapalı. Öğrenme kapalı olduğu için geçişler
  arasında taşma beklenmez, ama sıra değiştirilerek sınanmadı.
- "Fark" sütunu `otomatik düzeltme ya da yazılan` değerini karşılaştırır: hiç değiştirilmeyen kelime ile
  kendisine "düzeltilen" kelime aynı görünür.
- Emülatör ölçümüdür; dokunuşlar tuş merkezlerine `adb input tap` ile gönderilir, gerçek parmak
  dağılımı yoktur. Yazım hatası vakalarında HeliBoard'un yakınlık modeli bu yüzden en iyi koşulda çalışır.
- Kaydırarak yazma, kişisel sözlük ve öğrenme ölçülmez.

## Sonuçlar

Koşu: [43600969904](https://github.com/3mpe/KBoard/actions/runs/43600969904) (iş 132465424442),
2026-11-14, commit `a0e0c65d`. İki geçişte de eksiksiz vaka: 12 / 12 (44 kelime). Motor durumu kanıtı:
açık geçişte `motor anahtarı: açık`, 275 `düzeltme:` satırı; kapalı geçişte `motor anahtarı: kapalı`,
0 `düzeltme:` satırı. Aşağıdaki tablolar koşunun günlüğünden değiştirilmeden alınmıştır.

**Okurken:** 12 vaka yön gösterir, eşik belirlemeye yetmez; kırılımların çoğunda n tek hanelidir.
Bu örneklemde öne çıkanlar:

- Sonraki kelime (İlk-3 @0) motor kapalıyken %0'dır. Olası neden öğrenme kapalıyken HeliBoard'un boş
  alanda öneri vermemesidir (bu koşuda ayrıca doğrulanmadı); öyleyse bu sayı ayarın sonucudur ve
  kullanıcının kendi geçmişiyle çalışan stok HeliBoard için alt sınır sayılmalıdır.
- Yanlış otomatik düzeltme iki geçişte de 2/30'dur. Puanlayıcı büyük/küçük harfi ayırmaz
  (`eval/kboard_eval/metrics.py`, `same_word`): yalnız büyük/küçük harfi değişen kelime yanlış
  düzeltme sayılmaz. Bu yüzden motor kapalıyken görülen `dinlendim` → `Dinlendim` (`aksan-0034`)
  bu sayıda yoktur; kullanıcı açısından yine de istenmeyen bir değişikliktir.
  - Motor açık (2): `mi` → `mı` (`yazim-0038`) ve `yazim-0020`'deki ortak kelime.
  - Motor kapalı (2): `oturduk` → `oturdukları` (`aksan-0069`) ve `yazim-0020`'deki ortak kelime.
  - Ortak kelime: `yazim-0020`'de ("Egzos muayenesinden geçti.") doğru yazılmış iki kelimeden
    (`muayenesinden`, `geçti`) biri iki geçişte de aynı biçimde değişmiştir; ayrışma tablosunda bu
    yüzden görünmez. Etiket satırları bunu gösterir (`yaygin_hata` iki geçişte de 1/2, `harf_fazla`
    motor açıkken 1/2, `belirsiz` motor kapalıyken 1/1). Hangisi olduğu bu koşunun günlüğünden
    çıkarılamaz (iz dosyaları yalnız koşu ekindedir); `gorev=taban` artık bu kelimeleri özetine
    yazar, sonraki koşuda adı buraya işlenir.
- Aksan düzeltme 8/10'a karşı 6/10, yazım hatası düzeltme 2/4'e karşı 1/4: farklar birkaç kelimeden ibarettir.

### KBoard (motor açık)

| Kırılım | Kelime | İlk-3 @0 (sonraki kelime) | İlk-3 @1 | İlk-3 @2 | İlk-3 @3 | KSR | Aksan düzeltme (n) | Yanlış oto. düzeltme (n) | Yazım hatası düzeltme (n) | Yazım hatası ilk-3 |
|---|---|---|---|---|---|---|---|---|---|---|
| **Genel** | 44 | %21.9 | %25.0 | %34.9 | %56.4 | %34.6 | %80.0 (10) | %6.7 (30) | %50.0 (4) | %75.0 |
| kategori: dogru_yazim | 16 | %8.3 | %18.8 | %31.2 | %62.5 | %35.2 | – (0) | %0.0 (16) | – (0) | – |
| kategori: sohbet | 28 | %30.0 | %28.6 | %37.0 | %52.2 | %34.3 | %80.0 (10) | %14.3 (14) | %50.0 (4) | %75.0 |
| etiket: belirsiz | 4 | %0.0 | %0.0 | %0.0 | %33.3 | %21.9 | %66.7 (3) | %0.0 (1) | – (0) | – |
| etiket: deasciify | 15 | %27.3 | %26.7 | %33.3 | %46.2 | %34.7 | %80.0 (10) | %0.0 (5) | – (0) | – |
| etiket: harf_fazla | 3 | %0.0 | %33.3 | %0.0 | %50.0 | %19.0 | – (0) | %50.0 (2) | %0.0 (1) | %0.0 |
| etiket: komsu_tus | 7 | %60.0 | %42.9 | %71.4 | %60.0 | %46.7 | – (0) | %0.0 (5) | %50.0 (2) | %100.0 |
| etiket: sik_hata | 4 | %0.0 | %0.0 | %0.0 | %25.0 | %35.5 | – (0) | %0.0 (4) | – (0) | – |
| etiket: yabanci | 4 | %33.3 | %25.0 | %50.0 | %75.0 | %38.1 | – (0) | %0.0 (4) | – (0) | – |
| etiket: yaygin_hata | 3 | %0.0 | %0.0 | %0.0 | %66.7 | %23.1 | – (0) | %50.0 (2) | %100.0 (1) | %100.0 |
| etiket: yazim_hatasi | 13 | %33.3 | %30.8 | %41.7 | %60.0 | %33.7 | – (0) | %22.2 (9) | %50.0 (4) | %75.0 |

Gecikme: p50 – · p95 – · max – (0 ölçüm)

### HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı)

| Kırılım | Kelime | İlk-3 @0 (sonraki kelime) | İlk-3 @1 | İlk-3 @2 | İlk-3 @3 | KSR | Aksan düzeltme (n) | Yanlış oto. düzeltme (n) | Yazım hatası düzeltme (n) | Yazım hatası ilk-3 |
|---|---|---|---|---|---|---|---|---|---|---|
| **Genel** | 44 | %0.0 | %6.8 | %9.3 | %35.9 | %18.2 | %60.0 (10) | %6.7 (30) | %25.0 (4) | %75.0 |
| kategori: dogru_yazim | 16 | %0.0 | %0.0 | %0.0 | %37.5 | %18.1 | – (0) | %0.0 (16) | – (0) | – |
| kategori: sohbet | 28 | %0.0 | %10.7 | %14.8 | %34.8 | %18.3 | %60.0 (10) | %14.3 (14) | %25.0 (4) | %75.0 |
| etiket: belirsiz | 4 | %0.0 | %0.0 | %0.0 | %33.3 | %18.8 | %66.7 (3) | %100.0 (1) | – (0) | – |
| etiket: deasciify | 15 | %0.0 | %6.7 | %13.3 | %38.5 | %17.4 | %60.0 (10) | %20.0 (5) | – (0) | – |
| etiket: harf_fazla | 3 | %0.0 | %0.0 | %0.0 | %0.0 | %4.8 | – (0) | %0.0 (2) | %0.0 (1) | %0.0 |
| etiket: komsu_tus | 7 | %0.0 | %28.6 | %28.6 | %40.0 | %31.1 | – (0) | %0.0 (5) | %50.0 (2) | %100.0 |
| etiket: sik_hata | 4 | %0.0 | %0.0 | %0.0 | %0.0 | %19.4 | – (0) | %0.0 (4) | – (0) | – |
| etiket: yabanci | 4 | %0.0 | %0.0 | %0.0 | %25.0 | %4.8 | – (0) | %0.0 (4) | – (0) | – |
| etiket: yaygin_hata | 3 | %0.0 | %0.0 | %0.0 | %33.3 | %11.5 | – (0) | %50.0 (2) | %0.0 (1) | %100.0 |
| etiket: yazim_hatasi | 13 | %0.0 | %15.4 | %16.7 | %30.0 | %19.6 | – (0) | %11.1 (9) | %25.0 (4) | %75.0 |

Gecikme: p50 – · p95 – · max – (0 ölçüm)

### Boşlukta metne geçen kelime: iki geçişin ayrıştığı yerler

12 vaka, 44 kelime; farklı: 6

| Vaka | Yazılan | Hedef | KBoard (motor açık) | HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı) |
|---|---|---|---|---|
| `aksan-0030` | ugrayacagim | uğrayacağım | uğrayacağım | uğrayacağı |
| `aksan-0034` | dinlendim | dinlendim | dinlendim | Dinlendim |
| `aksan-0069` | oturduk | oturduk | oturduk | oturdukları |
| `aksan-0086` | Boyle | Böyle | Böyle | Boyle |
| `yazim-0020` | Egzos | Egzoz | Egzoz | Egzos |
| `yazim-0038` | mi | mi | mı | mi |

Gecikme bu koşuda ölçülmez (sürücü iz kaydına süre yazmıyor).

## Gboard yarısı: gerçek cihaz bekliyor

#10'un diğer yarısı (Gboard tabanı) bu belgede yoktur ve emülatörde ölçülmeyecektir. Gerçek bir
cihazda, o cihaza göre çıkarılmış bir tuş haritasıyla (`eval/keymaps/README.md`) ve aynı alt kümeyle
ölçülmesi gerekir; yöntem kararı `docs/OPEN_QUESTIONS.md` OQ-1'dedir. O ölçüm yapılana kadar bu belge
"kısmi" kalır ve Gboard ile ilgili hiçbir sayı içermez.

| Klavye | Cihaz | Durum |
|---|---|---|
| Gboard | — (gerçek cihaz) | bekliyor |
| Stok HeliBoard APK'sı | — | ölçülmedi; planlanmadı |
