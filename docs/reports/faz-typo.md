# Klavye farkında yazım hatası düzeltme (TypoSource)

Tarih: 2026-11-13 · Dal: `typo` (`sohbet-lm` üzerine) · Kapsam: yalnızca motor (`engine/`) ve
ölçüm; `app/` tarafına bağlanmadı.

**Karar (Fable incelemesi): oto. düzeltme varsayılan olarak kapalı.** Düzeltmeler yalnızca öneri
çubuğunda görünür. Oto. düzeltme açıkken korpusun ayrılmış diliminde doğru yazılmış kelimelerin
%0,45'i değiştiriliyor; eşik %0,3. Aşağıdaki Ölçüm bölümüne bakın.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Aday kaynağı (`CandidateSource`, `UserWordsAware`) | `engine/src/main/kotlin/io/github/kboard/engine/typo/TypoSource.kt` |
| Ağırlıklı uzaklık, Türkçe Q komşulukları | `engine/.../typo/TypoCost.kt` |
| CLI | `engine/.../cli/EvalCli.kt`: `--deasciify` verilince öneriler açık; `--no-typo` kapatır, `--typo-autocorrect` oto. düzeltmeyi açar (ölçüm için) |
| Testler | `engine/src/test/kotlin/io/github/kboard/engine/typo/TypoSourceTest.kt` (14 test) |
| Korpus ölçümü | `data/tools/corpus_false_ac.py` + CI adımı (`.github/workflows/ci.yml`, `engine-eval` işi) |

`TypoSource` aksan düzelticinin dizinini (`deasciify-tr.kbd`, 180 727 kayıt) kullanır, ek veri
gerektirmez. Yeni bir `Source` değeri eklenmedi (`Source.OTHER`).

Yapılandırma (`TypoSource.Config`): `autocorrect = false` (varsayılan) iken kaynak hiçbir adayı
`autocorrectable` işaretlemez. Üçüncü yapıcı parametresi `isKnownWord: (String) -> Boolean` dizin
dışında bilinen kelimeleri bildirir. EvalCli bunu dil modeline bağlar: `NgramModel.id(w) >= 0` ve
`suggestable` (yasak listesinde değil) olan her kelime bilinen kelimedir, hiç düzeltilmez ve
bilinmeyen sayılmaz (`UserWordSuffixSource` ile aynı lambda deseni).

### Aday üretimi

1. **Yalnızca bilinmeyen kelimede çalışır.** Yazılan, dizinde katlanmış biçimiyle ve yazılan
   aksanlarla uyumlu bir kayıt olarak varsa (`kasa`, `sus`; `cok` → çok aksan düzelticinin işi),
   kişisel sözlükteyse ya da dil modelinin önerilebilir sözcüğüyse hiç aday vermez.
2. **Tamamlama varsa (kelime yazılıyor olabilir):** aday yok. Tek istisna (Fable kabul etti):
   yazılan en az 6 harfse, en çok 16 uyumlu tamamlaması varsa ve bir düzeltme bu tamamlamaların
   hepsinden sıksa, en çok iki düzeltme *tüm sözlük tamamlamalarının altına* eklenir. Bu adaylar
   oto. düzeltilmez (`Geliyorm` → çubukta `Geliyormuş, Geliyor, Geliyorum`). Puanları
   tamamlamaların altında kaldığından kelime yazılırken tamamlamaları çubuktan itmez.
3. **Katlanmış uzayda düzenleme + dizin araması.** Yazılan katlanır, ekleme, değiştirme, silme ve
   komşu yer değiştirme ile uzaklık-1 anahtarlar üretilir ve dizinde tam eşleşme aranır. Aksan geri
   getirme böylece kendiliğinden birleşir: `gelyorum` → `geliyorum`, `cikiyrum` → `çıkıyorum`.
4. **Budama.** P = yazılanın dizindeki en uzun geçerli öneki. P'nin ötesindeki bir düzenleme kelimeyi
   düzeltemez. Eklenen/değiştirilen harf, dizinde o önekten sonra gerçekten gelen harflerden seçilir;
   konumlar geçersizleşme noktasından geriye doğru taranır.
5. **Uzaklık 2** yalnızca en az 6 harfli kelimelerde aranır. İlk düzenleme kelimeyi ilerletmeli;
   ikinci düzenleme ancak kelimenin yeniden geçersizleştiği yerde (±1) yapılır. Sorgu başına en çok
   1 500 dizin araması yapılır; tamamlamalı önekte 150 (yalnızca uzaklık 1).
6. Saldırgan bayraklı kayıtlar aday olmaz. Kişisel sözlük kelimeleri (uzunluk farkı ≤ 2) sıklık 255
   ile aday olabilir. Rakam, kesme işareti ya da harf dışı karakter içeren yazımda kaynak susar.

### Maliyet (TypoCost, ağırlıklı OSA)

| İşlem | Maliyet |
|---|---|
| aksansız yazım (s yerine ş) | 0 (aksan geri getirme) |
| aksan çifti ters yönde (ş yerine s, ı yerine i...) | 0,3 |
| komşu tuş (Türkçe Q; aynı sırada yan yana ya da üst/alt sırada çapraz) | 0,6 |
| ğ ↔ y (`deyil` → değil) | 0,7 |
| ünsüz yumuşaması çifti (b/p, d/t, k/g, ğ/k) | 0,9 |
| başka harf | 1,0 |
| yer değiştirme | 0,6 |
| fazla harf: sonda ikilenme / ortada ikilenme / önceki tuşa komşu / diğer | 0,45 / 0,75 / 0,75 / 0,85 |
| eksik harf: ikilenen / diğer | 0,5 / 0,7 |
| ilk harfi farklı aday (ek) | +0,4 |

Klavye sıraları `q w e r t y u ı o p ğ ü` / `a s d f g h j k l ş i` / `z x c v b n m ö ç`'dir; tuş
merkezleri 0,5 / 1,0 / 2,0 tuş kaydırmalıdır (3. sırada Shift var). Merkezleri arası ≤ 1,5 tuş
olan tuşlar komşu sayılır. Sıralama puanı: `3 × sıklık/255 − maliyet`.

## Kurallar

Düzeltme öneri çubuğuna her zaman girebilir. `autocorrectable` yalnızca `Config.autocorrect = true`
iken ve aşağıdakilerin hepsi sağlanırsa verilir:

1. Yazılan bilinmiyor: dizinde uyumlu kayıt, kişisel sözlükte, koruma listesinde ya da dil modelinde
   (önerilebilir sözcük olarak) yok; dizinde tamamlaması da yok.
2. Yalnızca harf; ilk harften sonra büyük harf yok (`ÜZERW`, `üZerw` düzeltilmez).
3. En az 3 harf.
4. En iyi adayın maliyeti ≤ 0,7, yani tek bir "ucuz" hata.
5. Yalnızca uzaklık 2 ile ulaşılan bir adaya, kelime 8 harften kısaysa asla.
6. En iyi aday ikinciden sıralama puanında ≥ 0,25 önde.
7. Tamamlamalı önek istisnasından gelen adaylar hiçbir zaman oto. düzeltilmez.

Varsayılanın açılma koşulu (Fable): ayrılmış korpus diliminde yanlış oto. düzeltme ≤ %0,3 **ve**
seed ölçüm eşiği geçiyor. Şu an ilki sağlanmıyor (%0,45), varsayılan kapalı kaldı.

Eşikler yalnızca seed setinin geliştirme yarısında (`zlib.crc32(id) % 2 == 0`) ayarlandı. Test
yarısındaki vakalar ayar için okunmadı. Dil modeli korumasından ve varsayılanın kapatılmasından
sonra eşiklere dokunulmadı; korpus dilimi ayar için kullanılmadı.

## Ölçüm

### Korpus: ayrılmış dilimde yanlış oto. düzeltme

`data/tools/corpus_false_ac.py`, CI'da `engine-eval` işinin son adımı:

1. İki korpusun (Common Voice ve sohbet-v1) her 10. dolu satırı ayrılır.
2. Dil modeli geri kalan satırlardan CI'daki `buildNgramModel` ayarlarıyla derlenir (sohbet 3 kat
   ağırlık, seed sızıntı süzgeci, yasak listesi).
3. Ayrılan 5 058 satırdaki kesmesiz, yalnızca harften oluşan 21 687 sözcük (9 438 ayrı biçim) doğru
   yazılmış sayılır ve motora boş bağlamla, tam kelime olarak sorulur.

CI, varsayılan yapılandırmanın oranı %0,3'ü aşarsa başarısız olur.

| Motor (`--deasciify --ngram`, dilimsiz dil modeli) | Yanlış oto. düzeltme | Örnekler |
|---|---|---|
| `--no-typo` | 8 / 21 687 = %0,04 | Hosça→Hoşça, Sahi→Şahı, adi→adı (aksan düzelticiden) |
| **varsayılan (öneri var, oto. düzeltme kapalı)** | **8 / 21 687 = %0,04** | aynı 8 |
| `--typo-autocorrect` | 97 / 21 687 = %0,45 | Haşan→Hasan, aradınız→aracınız, Eriyorum→Ediyorum, Getirdiğiniz→Getirdiğimiz, Hazırlanmam→Hazırlanmak, Hürriyetime→Hürriyetine |

Fable'ın denemesi (`sohbet-v1`in tüm doğru yazılmış sözcükleri, 18 819 sözcük, boş bağlam; dil
modeli bu korpusu içerdiği için dilimli ölçümden iyimser):

| Yapılandırma | Yanlış oto. düzeltme |
|---|---|
| TypoSource yok | 5 (%0,03) |
| ilk sürüm (oto. düzeltme açık, dil modeli koruması yok) | 208 (%1,1) |
| oto. düzeltme açık + dil modeli koruması | 8 (%0,04): yapıyosun→yapıyorsun gibi yasak listesindeki konuşma dili biçimleri |
| varsayılan (oto. düzeltme kapalı) | 5 (%0,03) |

Dil modeli koruması, korpusta geçen çekimleri (Annemin, telefonumu, atıyorum, alamıyorum)
kurtarıyor. Ayrılmış dilimde kalan hatalar dil modelinde de olmayan çekimler: aradınız, eriyorum,
hazırlanmam.

### Seed seti (dil modeli açık: `--deasciify ... --ngram ...`)

Geliştirme yarısı (dev) 162 vaka, test yarısı 165 vaka. Gecikme, eval'in uçtan uca ölçtüğü p95'tir;
makine başka işlerle paylaşımlıydı ve aynı ayarla tekrar ölçümlerde ±0,5 ms oynadı.

| Küme | Yapılandırma | Yazım hatası İlk-1 (n) | Yazım hatası İlk-3 | Aksan düzeltme | Yanlış oto. düzeltme (n) | İlk-3 @0 | İlk-3 @3 | KSR | p95 |
|---|---|---|---|---|---|---|---|---|---|
| dev | `--no-typo` | %0,0 (0/29) | %0,0 | %77,4 | %0,23 (1/434) | %21,2 | %53,0 | %40,2 | 1,04 ms |
| dev | **varsayılan** | %0,0 (0/29) | **%79,3** | %77,4 | %0,23 (1/434) | %21,2 | %53,0 | %40,3 | 1,70 ms |
| dev | `--typo-autocorrect` | %55,2 (16/29) | %79,3 | %77,4 | %0,23 (1/434) | %21,2 | %53,0 | %40,3 | 1,46 ms |
| test | `--no-typo` | %0,0 (0/28) | %0,0 | %78,8 | %0,00 (0/470) | %20,2 | %51,6 | %39,5 | 1,36 ms |
| test | **varsayılan** | %0,0 (0/28) | **%64,3** | %78,8 | %0,00 (0/470) | %20,2 | %51,6 | %39,5 | 1,41 ms |
| test | `--typo-autocorrect` | %42,9 (12/28) | %64,3 | %78,8 | %0,00 (0/470) | %20,2 | %51,6 | %39,5 | 1,36 ms |
| tam | `--no-typo` | %0,0 (0/57) | %0,0 | %78,2 | %0,11 (1/904) | %20,7 | %52,3 | %39,8 | 0,69 ms |
| tam | **varsayılan** | %0,0 (0/57) | **%71,9** | %78,2 | %0,11 (1/904) | %20,7 | %52,3 | %39,9 | 1,23 ms |
| tam | `--typo-autocorrect` | %49,1 (28/57) | %71,9 | %78,2 | %0,11 (1/904) | %20,7 | %52,3 | %39,9 | 0,97 ms |

CI ölçüm eşiği (`.github/ci/eval-gate.py`, tam küme) varsayılanda ve `--typo-autocorrect` ile
geçiyor: aksan %78,2, yanlış oto. düzeltme %0,1, İlk-3 @0 %20,7, @1 %30,6, @3 %52,3, KSR %39,9,
kişisel sözlük İlk-3 @3 %47,5.

Dil modeli koruması seed setindeki tek yeni yanlış düzeltmeyi (`ararım` → aradım, test yarısı)
kaldırdı. Test yarısındaki yazım hatası İlk-3 dil modelsiz %67,9 iken modelle %64,3: dil modeli
adayları çubuktaki yerleri paylaşıyor, ayrıca bazı yazım hatalı biçimler modelde bilinen kelime.

Dil modelinden önceki ölçüm (yalnızca `--deasciify`, oto. düzeltme açık), karşılaştırma için:
dev İlk-1 %55,2 / İlk-3 %79,3, test %42,9 / %67,9; tam kümede yanlış oto. düzeltme 2/904.

Hedefler (İlk-1 ≥ %55, İlk-3 ≥ %75): oto. düzeltme açıkken geliştirme yarısında tutuyor, test
yarısında tutmuyor. Varsayılan kapalıyken İlk-1 tanım gereği %0.

Kaynağın kendi süresi (ısınmış JVM, seed setindeki tüm önekler, 7 527 sorgu; dil modelinden önce
ölçüldü): p50 ~1,5 µs, p95 0,2–0,27 ms, p99 1,2–1,8 ms, en kötü 4–6 ms.

## Sınırlamalar

- **Oto. düzeltme kapalı.** Kalan yanlış düzeltmeler sözlükte de dil modelinde de olmayan doğru
  çekimler (aradınız → aracınız, hazırlanmam → hazırlanmak). Kişi ekleri (m/n, k/z) komşu ya da
  ucuz düzenlemeyle başka bir çekime dönüşüyor. Açmak için seçenekler: daha büyük bir sohbet
  korpusu, oto. düzeltmeyi bağlamdaki dil modeli olasılığına bağlamak, "kök + bilinen ek"
  geçerlilik denetimi. Her seferinde `corpus_false_ac.py` ile ölçülmeli.
- **Sözlükteki yanlış yazımlar:** `orjinal`, `tesbit`, `yanlız`, `sesiz`, `laboratuar`,
  `teşekürler`, `entellektüeller` dizinde var; "bilinen" sayıldıkları için düzeltilmiyorlar.
  Dil modeli koruması aynı etkiyi dil modelindeki yanlış yazımlar için de yapar. Sık yanlışlar
  listesi elle eklenmedi: seed setindeki örneklerle aynı kelimeleri içerirdi.
- **Hedefi sözlükte olmayan düzeltmeler** (`antrenmandan`, `pantolonumu`, `eczaneye`) bulunamıyor.
- **Bağlam kullanılmıyor:** sıralama yalnızca sözlük sıklığı ve maliyetle yapılıyor (`herkes`/`merkez`,
  `yemeğe`/`yenge`). Dil modeli olasılığı sıralamaya henüz katılmıyor.
- **Arama bütçesi:** uzaklık 2 araması 1 500 dizin aramasıyla sınırlı. En kötü sorgu masaüstünde
  4–6 ms; Android'de daha yavaş olabilir.
- **Ölçek:** seed setinde yazım hatası yalnızca 57 kelime; bir örnek ~3,5 puan.
- **Kapsam dışı:** kesme işaretli ve rakam içeren yazımlar. Uygulamaya (`app/`) henüz bağlanmadı.
