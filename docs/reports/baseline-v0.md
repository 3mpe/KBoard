# Ölçüm tabanı v0 (kısmi)

Durum: **kısmi, iskelet.** Bu belgede henüz ölçülmüş sayı yoktur; tablolardaki `—` yer tutucudur.
Emülatör görevi (`taban`) hazırlandı ama bu belge yazıldığında bir kez bile koşulmadı. Gboard yarısı
gerçek cihaz bekliyor (aşağıda). İlgili işler: #10 (taban ölçümünün bu yarısı), #2, #3; #19 için
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
  debug `düzeltme:` satırlarını sayar. Motor kapalı geçişte `düzeltme:` satırı sıfır olmalı, açık
  geçişte en az bir olmalı; değilse geçiş raporda **GEÇERSİZ** diye işaretlenir ve iş kırmızı biter.
- Release denetimi: `.github/ci/debug-anahtar-kontrol.sh` release APK'nın dex'inde
  `kboard_debug_engine_off` ve `TestFieldActivity` adlarının geçmediğini denetler (`cihaz-olcum.yml`,
  `gorev=surum-duman`). `release.yml`'e bağlanmadı; bağlanması önerilir.

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
  ayar turu için yaklaşık 15 dk varsayımıyla iş yaklaşık 47 dk. Bu tahmindir, ölçülmedi.
  Geçiş başına 1260 sn süre sınırı var; dolunca yeni vakaya başlanmaz.
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
  eşik belirlemeye yetmez. Daha büyük taban için sürücünün hızlanması ya da birden çok koşu gerekir.
- Emülatör ölçümüdür; dokunuşlar tuş merkezlerine `adb input tap` ile gönderilir, gerçek parmak
  dağılımı yoktur. Yazım hatası vakalarında HeliBoard'un yakınlık modeli bu yüzden en iyi koşulda çalışır.
- Kaydırarak yazma, kişisel sözlük ve öğrenme ölçülmez.

## Sonuçlar

Koşu: — (tarih, commit ve iş bağlantısı koşudan sonra yazılacak). İki geçişte de eksiksiz vaka: — / 12.

| Kırılım | Metrik | KBoard (motor açık) | HeliBoard eşdeğeri (KBoard derlemesi, motor kapalı) |
|---|---|---|---|
| Genel | İlk-3 @0 (sonraki kelime) | — | — |
| Genel | İlk-3 @1 / @2 / @3 | — | — |
| Genel | KSR | — | — |
| etiket: deasciify | Aksan düzeltme (n) | — | — |
| kategori: dogru_yazim | Yanlış oto. düzeltme (n) | — | — |
| etiket: yazim_hatasi | Yazım hatası düzeltme (n) | — | — |
| etiket: yazim_hatasi | Yazım hatası ilk-3 | — | — |

Tam tablolar (bütün kategori ve etiketler) koşudan sonra `puan-acik.md` ve `puan-kapali.md`'den buraya
aktarılacak. Boşlukta ayrışan kelimeler: — (`fark.md`).

## Gboard yarısı: gerçek cihaz bekliyor

#10'un diğer yarısı (Gboard tabanı) bu belgede yoktur ve emülatörde ölçülmeyecektir. Gerçek bir
cihazda, o cihaza göre çıkarılmış bir tuş haritasıyla (`eval/keymaps/README.md`) ve aynı alt kümeyle
ölçülmesi gerekir; yöntem kararı `docs/OPEN_QUESTIONS.md` OQ-1'dedir. O ölçüm yapılana kadar bu belge
"kısmi" kalır ve Gboard ile ilgili hiçbir sayı içermez.

| Klavye | Cihaz | Durum |
|---|---|---|
| Gboard | — (gerçek cihaz) | bekliyor |
| Stok HeliBoard APK'sı | — | ölçülmedi; planlanmadı |
