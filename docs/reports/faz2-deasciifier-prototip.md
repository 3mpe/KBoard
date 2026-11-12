# Faz 2 prototipi: sözlük tabanlı deasciifier (#20)

Tarih: 2026-11-12 · Dal: `deasciifier` · Yaklaşım: EVALUATION.md §2 "B birincil, A yedek" — bu
prototipte yalnızca **B** var; A (Yuret desenleri) yapılmadı (bkz. açık işler).

## Ne yapıldı

| Parça | Dosya |
|---|---|
| `main_tr.dict` çözücüsü (Python stdlib) | `data/tools/dict_dump.py` |
| Dizin biçimi + derleyici | `engine/src/main/kotlin/io/github/kboard/engine/deasciify/DeasciifyIndex.kt` |
| Aday kaynağı | `engine/.../deasciify/DeasciifySource.kt` |
| Derleme / ölçüm aracı | `engine/.../deasciify/DeasciifyTool.kt` (`build`, `bench`) |
| Oto. düzeltme koruma listesi | `data/deasciify-koruma.txt` (25 kelime, elle) |
| CLI bayrağı | `engine/.../cli/EvalCli.kt`: `--deasciify <dizin.kbd>`, `--deasciify-valid-margin <n\|off>` |
| Testler | `engine/src/test/.../deasciify/DeasciifySourceTest.kt` |

### 1. Kelime listesi

HeliBoard'da v2 sözlük **çözücüsü yok** (yalnızca `Ver2DictEncoder` ve `FormatSpec` var); çözücü
bu ikisinden tersine yazıldı. `main_tr.dict`: sihirli sayı `9BC13AFE`, sürüm 202, kod noktası tablosu
yok, sözlük sürümü 54 (2014). Patricia trie gezilerek 214 393 düğümden **180 841 kelime** (0..255
sıklık, 137'si "saldırgan" bayraklı) çıktı. Çıktı `data/out/` altında, git dışı.

Not: liste Vikipedi ağırlıklıdır (`köy`, `km`, `ilçesine` en sıklar arasında) ve korpustaki
aksansız yazımları da içerir (`simdi` 99, `aksam` 99, `artik` 100, `koy` 126...). Bu, kuralın
"yazılan biçim sözlükte var mı?" sorusunu tek başına kullanamamasının nedenidir.

### 2. Dizin biçimi (`.kbd`)

```
"KBDA" | u8 sürüm | u32 kayıt sayısı | { u8 uzunluk | UTF-8 biçim | u8 sıklık | u8 bayrak }*
```

Kayıtlar katlanmış anahtara (`TurkishText.fold`) göre bayt sırasında, eşitlikte azalan sıklıkta.
Anahtar dosyada saklanmaz: ikili aramada UTF-8 baytları anında katlanır (ç/ğ/ı/İ/ö/ş/ü/â/î/û ve
ASCII büyük harf). Bu katlamanın `TurkishText.fold` ile aynı sonucu vermediği kelimeler (ör. `É`
içerenler) derlemede atlanır. Aynı küçük harf biçimi tek kayda birleştirilir (`İstanbul`/`istanbul`).
Önek sorgusu = iki ikili arama + aralıkta sıklık baytı taraması.

### 3. Aday ve oto. düzeltme kuralı

- Aday: yazılanın katlanmış hâliyle **tam** eşleşen biçimler (her zaman; "mi" → mi, mı) + katlanmış
  hâli o önekle başlayan en sık tamamlamalar. Yazılanda aksanlı harf varsa (ç, ş, ı, İ...) aday aynı
  konumda aynı harfi taşımalı. Büyük harf düzeni yazılandan taşınır (`Simdi` → `Şimdi`, `ISIK` → `IŞIK`).
- Kesme işareti: kök aksanlanır, ek sözlükten ünlü uyumuna uyan biçimle seçilir (`Kadikoy'u` → `Kadıköy'ü`).
- Kullanıcı sözlüğü (`UserWordsAware`): sıklık 255 sayılır, aynı küçük harf biçimli sözlük kaydını ezer.
- `autocorrectable` yalnızca şu koşulların hepsinde (sıklıklar 0..255 sözlük puanı):
  1. katlanmış biçimi yazılanla aynı en az bir biçim var ve en sık olanı yazılanın kendisi değil;
  2. yazılan biçim sözlükte varsa: koruma listesinde değil, ASCII (ya da saldırgan bayraklı), ve en
     iyi biçim ondan **≥ 30** önde (`validWordMargin`);
  3. en iyi biçim, yazılan dışındaki ikinci biçimden **≥ 20** önde (`dominanceMargin`).

  Sonuç: `cok`→çok, `simdi`→şimdi (138/99), `aksam`→akşam; `kar` (yazılan en sık), `mi` (mı 5 geride),
  `Su` (şu 7 geride), `sus` ve `ilik` (koruma listesi) **düzeltilmez**. `sik` saldırgan bayraklı
  olduğundan düzeltilir, ama sık/şık arasında bağlamsız karar verilemez (sık 150 > şık 104 seçilir).
- Koruma listesi: sözlükte aksanlı eşi ≥30 önde olan 506 ASCII biçimin en sık ~400'ü elle gözden
  geçirildi; yazıldığı gibi de yaygın gerçek kelime olanlar (koy, koyun, sus, yas, tas, iste, buyur,
  ilik...) listeye alındı. Kelimeler `sus` ve `ilik` test setinde de geçiyor; listeye sözlük
  incelemesiyle girdiler, ama küçük seed setiyle bu ayrım tam bağımsız sayılmaz.

## Sonuçlar (seed-v0, 31 vaka, 137 kelime)

| Öngörücü | İlk-3 @1 | @2 | @3 | KSR | Aksan düzeltme (n=28) | Yanlış oto. düzeltme (n=109) | Gecikme p50 / p95 |
|---|---|---|---|---|---|---|---|
| Faz 1.7 motoru (kaynak yok) | %0,0 | %0,0 | %0,0 | %0,0 | %0,0 | %0,0 | 0,07 / 0,14 ms |
| `freq` öngörücü, aynı kelime listesi | %8,0 | %13,2 | %24,8 | %18,8 | %64,3 | %0,0 | 0,54 / 87,9 ms |
| **DeasciifySource (varsayılan)** | %8,0 | %13,2 | %23,9 | %18,1 | **%82,1** | **%0,0** | 0,11 / 0,55 ms |
| DeasciifySource, `--deasciify-valid-margin off` | %8,0 | %13,2 | %23,9 | %18,1 | %67,9 | %0,0 | 0,16 / 1,28 ms |

Gecikmeler eval'in uçtan uca ölçümüdür (Python ↔ JSON-satır ↔ JVM). Etiket kırılımı (varsayılan):
`deasciify` %82,1 (28), `belirsiz` %69,2 (13), `buyuk_I` %75,0 (4), `kesme` %100 (2),
`oto_duzeltme_tuzagi` yanlış düzeltme %0 (21).

Kaçan 5 aksan düzeltmesi: `mi`→mı, `Su`→Şu, `sik`→şık (üçü de `belirsiz`, bağlam gerekir; doğru biçim
öneri çubuğunda ilk 2'de), `ilik`→ılık (koruma listesi; ılık çubukta 1.), `konusuruz`→konuşuruz
(sözlükte yok — A yedeği ya da morfoloji gerekir).

Tamamlama metrikleri (İlk-3, KSR) `freq` öngörücüyle aynı düzeyde: veri aynı, sıralama yalnızca
sıklık. KSR'deki 0,7 puanlık düşüş, tam eşleşen aksanlı biçimlerin tamamlama yerlerini almasından.
Sonraki kelime bu kaynağın işi değil (@0 = %0).

## Sonuçlar (seed-v0 tam set, 327 vaka, 1250 kelime) — 2026-11-12

Test seti 31 vakadan 327 vakaya büyüdükten sonra yeniden ölçüldü. Kod ve eşikler değiştirilmedi.
**Ayrılmış alt küme:** ayarlama sırasında hiç görülmemiş 297 vaka (ilk 30 vaka hariç). Koruma listesi
ve eşikler yalnızca ilk 30 vakayla ayarlandığı için aşırı uyumun ölçüsü bu satırlardır.

| Öngörücü | Küme | İlk-3 @1 | @3 | KSR | Aksan düzeltme (n) | Yanlış oto. düzeltme (n) | Gecikme p95 |
|---|---|---|---|---|---|---|---|
| Faz 1.7 motoru (kaynak yok) | 327 | %0,0 | %0,0 | %0,0 | %0,0 (289) | %0,0 (904) | 0,50 ms |
| `freq` öngörücü | 327 | %7,9 | %26,7 | %18,1 | %67,5 (289) | %0,2 (904) | 113 ms |
| **DeasciifySource (varsayılan)** | 327 | %8,2 | %24,7 | %17,1 | **%78,2** (289) | **%0,1** (904) | 1,37 ms |
| DeasciifySource, margin off | 327 | %8,2 | %24,7 | %17,1 | %69,6 (289) | %0,1 (904) | 0,95 ms |
| **DeasciifySource (varsayılan)** | ayrılmış 297 | %8,3 | %24,9 | %17,1 | **%77,7** (265) | **%0,1** (796) | – |
| `freq` öngörücü | ayrılmış 297 | %7,9 | %27,0 | %18,1 | %67,5 (265) | %0,3 (796) | – |

Yorum: ayrılmış kümede aksan doğruluğu %82,1'den %77,7'ye iniyor; düşüş küçük, aşırı uyum belirtisi
sınırlı. `freq` öngörücüsüne göre fark +10 puan ve korunuyor. Etiket kırılımı: `belirsiz` %62,3 (61),
`buyuk_I` %73,3 (15), `kesme` %91,7 (12), `oto_duzeltme_tuzagi` yanlış düzeltme %0,9 (115).

Tek yanlış oto. düzeltme: `copunu` → `çöpünü` (hedef `copunu`, "cop" = polis sopası). İncelenen diğer
kaçaklar:
- `sik`→sık (hedef şık), `uc`→uç (hedef üç), `Basim`→Basım (hedef Başım), `Isim`→İsim (hedef İşim).
  Hepsi bağlam ister (Faz 4).
- `kalmayacagim` → `Kalmayacağım`: sözlükte kelime yalnız büyük harfle var (cümle başı artığı). Faz 4
  korpusundan sözlük yeniden üretilince düzelir; o zamana kadar bilinen sınırlama.
- `Tesekurler` → `Teşekürler`: yazım hatası + aksan birlikte; yazım düzeltme kapsam dışı.

## Boyut, yükleme, bellek, gecikme

`DeasciifyTool bench` (OpenJDK 21, 4 çekirdekli konteyner; Android değil):

| Ölçüm | Değer |
|---|---|
| Kayıt | 180 727 |
| Dosya | 2 485 046 bayt (gzip -9: 852 699; APK içinde sıkıştırılmış olur) — kaynak `main_tr.dict` 1 195 386 bayt |
| Bellekte dizin | 3,1 MB (2,4 MB veri dizisi + 0,7 MB ofset dizisi); ölçülen yığın artışı ~4,8 MB |
| Yükleme (dosyayı oku + ofsetleri hesapla) | 16–23 ms |
| Sorgu (2000 rastgele kelimenin tüm önekleri, 19 163 sorgu) | p50 3,8 µs · p95 65 µs · p99 100 µs · max 2,1 ms |
| En kötü önek (tek harf, ör. `k`: ~20 bin kaydın taranması) | ~140 µs |

Birim testindeki 200 bin kayıtlı sentetik dizinde p95 0,1 ms (eşik 5 ms). Isınmamış JIT'te ilk
sorgular (ör. ilk kesme işaretli sorgu) birkaç ms sürebiliyor.

Android notu: `read()` dosyanın tamamını bir `ByteArray`'e alır; Faz 2'nin uygulama tarafında aynı
biçim `assets`'ten `MappedByteBuffer` ile okunarak yığın dışında tutulabilir (biçim buna uygun,
kod değişikliği gerektirir).

## Sınırlamalar ve açık işler

1. **A yedeği (Yuret desenleri) yapılmadı.** Sözlükte olmayan biçimler (`konusuruz`) için gerekli.
   Bir port seçilmeden önce lisansı kaynağından doğrulanıp `data/KAYNAKLAR.md`'ye yazılmalı.
2. **Bağlam yok.** `sik`→sık/şık, `mi`/`mı`, `su`/`şu` gibi belirsizlikler n-gram (Faz 4) ister.
3. **Veri:** `main_tr.dict` Vikipedi ağırlıklı ve aksansız kopyalar içeriyor; koruma listesi elle
   yazıldı ve eşikler (20/30) yalnızca bu sözlük ve küçük seed setiyle ayarlandı. Daha büyük ve
   sohbet ağırlıklı bir test seti ile yeniden ayarlanmalı. `aosp-dictionaries` lisansı hâlâ
   "doğrulanacak".
4. Kesme işaretli kelimelerde ek seçimi yalnızca ilk ünlüye bakıyor; ünsüz benzeşmesi
   (`'de`/`'te`) yazılana bırakılıyor.
5. Uygulamaya (`app/`) bağlanmadı; Android'de yükleme ve gecikme ölçülmedi.
