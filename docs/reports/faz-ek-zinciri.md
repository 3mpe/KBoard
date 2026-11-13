# Veriye dayalı ek zinciri tamamlaması (plan adım 6)

Tarih: 2026-11-13 · Dal: `suffix` (main d54d2f17 üzerine) · Kapsam: motor (`engine/`), veri
(`data/suffix/`), ölçüm, `app/` bağlantısı ve CI.

**Özet.** Bilinen bir kök (en az 3 harf) tamamen yazıldıktan sonra, kökün sınıfına göre korpustan
sayılmış ek zincirleri öneriliyor. Kaynak **yalnız boşluk dolduruyor**. Sözlükte ya da dil
modelinde olan biçimleri önermiyor, yani bilinen tamamlamaları yeniden sıralamıyor. Temel
kaynaklar en az 3 aday verdiyse üretilen biçimler ilk 3'ün en çok birini alıyor.

Bu, Fable'ın (b) kararı. İlk sürüm (47cd8284) bilinen tamamlamaları da yeniden sıralıyordu. O
sürümde seed-v0'da ilk 3 önek olaylarının %24'ünde değişti; hedef 104 olayda ilk 3'e girdi, 108
olayda çıktı. Net etki sıfırdı.

Sonuç:

- **Birleştirme kuralı tuttu.** Kaybedilen olay 14, kazanılan 43. Genel @3 dev'de ve testte
  değişmedi. KSR dev'de +0,3, testte +0,4.
- **OOV erişimi arttı.** Seed dev'de 12/34'ten 20/34'e, testte 19/46'dan 32/46'ya. Korpusun
  ayrılmış diliminde %0'dan %52,7'ye.
- **Ertelenen hedefler.** "ek" etiketi (+3/+3) ve genel KSR (+1,0) hedefleri bir sonraki test
  setine ertelendi (Fable kararı). Seed-v0'da bu hedefleri ölçecek kadar OOV çekim yok; yeni sette
  daha çok OOV çekim gerekiyor.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Zincir tablosu üretici (masaüstü, Zemberek) | `data/tools/build_suffix_chains.py`, `tools/zemberek-spike/.../SuffixChains.java` (`analyze`, `lexicon`, `validate` kipleri) |
| Zincir tablosu (depoda, CC0-1.0) | `data/suffix/chains-tr.tsv` (21,7 KB; N 411, P 17, V 830 zincir) |
| Kök sınıfı tablosu (depoda, Apache-2.0: Zemberek-NLP 0.17.1'den türetildi, `NOTICE`) | `data/suffix/stems-tr.tsv` (24 369 kök: N 22 932, V 1 301, NV 136) |
| Kök bayrakları | `DeasciifyIndex` bayrak baytının boş bitleri (`FLAG_STEM_*`); `DeasciifyTool build` 5. argüman. Dizin boyutu değişmedi |
| Uyum (tek uygulama) | `engine/.../morph/SuffixGenerator.kt`: `Stem`, `Partial`, `start`/`append`; yeni şablonlar `^Iyor`, `R`, `P`, `T`, `K`, `(s)`, `(ş)`, `(I)`, `C` |
| İkili model | `engine/.../morph/SuffixChainModel.kt` (`.kbs` sürüm 1; sınıf başına önek ağacı, alt ağaç üst sınırı) |
| Aday kaynağı | `engine/.../morph/SuffixChainSource.kt` (`morph-chain`, `Source.MORPHOLOGY`, `GapFillSource`) |
| Boşluk doldurma | `engine/.../Api.kt` (`GapFillSource`), `SuggestionEngine.mergeGapFill` |
| Araç | `engine/.../morph/SuffixTool.kt` (`build`, `check`, `bench`) |
| Derleme | `engine/build.gradle.kts`: `buildSuffixModel` → `build/kboard/suffix-tr.kbs`; `buildDeasciifyIndex` kök tablosunu okur |
| CLI | `EvalCli`: `--suffix <model.kbs>` (ortam `KBOARD_SUFFIX`), `--suffix-params anahtar=değer,...` |
| Korpus ölçümü | `data/tools/suffix_corpus_eval.py` |
| Uygulama | `KBoardEngineLoader` (`kboard/suffix-tr.kbs`, dizinle birlikte bağlanır), `KBoardSuggestions.setSuffix` + `rebuild()`, `app/build.gradle.kts` (`KBoardAssetsTask`) |
| CI | `ci.yml`: `buildSuffixModel`; seed, kişisel ve korpus adımlarında `--suffix`; APK'da varlık denetimi ve 100 KB boyut eşiği; olay karşılaştırması uyarısı (`.github/ci/suffix-events.py`, CI'ı kırmaz) |
| Testler | `engine/src/test/.../morph/SuffixChainTest.kt` (8 test); motor testlerinin hepsi geçiyor |

### Veri hattı

1. **Korpus.** Common Voice (ağırlık 1) ve sohbet korpusu (ağırlık 3), 50 434 cümle. Dil modeliyle
   aynı sızıntı süzgeci kullanıldı: seed-v0 ve kisisel-v0 ile 4-gram paylaşan cümle atıldı.
2. **Bölütleme.** Zemberek 0.17.1 `analyzeAndDisambiguate` ile yapıldı, yalnız masaüstünde.
   Uygulama derlemesi Zemberek'e bağlı değildir. Derleme yalnız TSV'leri okur.
3. **Soyutlama.** Biçimbirim dizisi `SuffixGenerator` şablonlarına çevrildi
   (`Pnon+A3pl+P1sg+Loc` → `lAr+(I)m+DA`). 190 sözcük çevrilemedi.
4. **Doğrulama.** Tablodaki her zincir motorun kendi üreteciyle (`SuffixTool check`) korpustaki
   sözcüklerden yeniden üretildi. Uyuşma %99,9 (zincir başına en çok 400 örnek). Uyuşması %90'ın
   altında kalan zincir atıldı.
5. **Bütçe.** Ağırlıklı sayısı en az 2 olan zincirler alındı. Atılan seyrek zincirler "eksiz"
   satırına eklendi.

Her iki tablo ve ikili model yeniden üretildiğinde bayt bayt aynı çıkıyor. Bu denetlendi.

### Puanlama

Puan, dil modelinin ölçeğinde: `0,95 + 0,05 · log10 P`.

- **Biçim tahmini.** P(biçim | bağlam) ≈ P(kök | bağlam) · P(zincir | sınıf).
- **Kök kütlesi.** Kökün yalın hâli ve dil modelinde bilinen biçimlerinden oranla kestirilir:
  Σ P_LM / Σ P(zincir). Sözlük sıklığından gelen bir ön kestirime çekilir.
- **Bilinen biçim** (sözlükte ya da dil modelinde) önerilmez; yalnız kök kütlesi kestirimine girer.
  Bilinen tamamlamaların puanı ve sırası kendi kaynaklarında kalır.
- **Bilinmeyen biçim** `oovMargin` = 0,3 (log10) geride başlar. Yalnız log10 P(zincir) ≥ −4 ise
  önerilir. Kök başına en çok 4 dal açılır (`perStem`); bu sayıya bilinen biçimler de dahildir.
- **Yuva sınırı** (`SuggestionEngine`). Üretilen biçimler temel adaylarla puana göre birleşir.
  İlk 3 yuvada en çok max(1, boş yuva) üretilmiş biçim olur. Temel aday sayısına yazım hatası
  önerileri girmez. Temel adayların kendi sırası değişmez. Temelde 3'ten az aday varsa hiçbiri
  ilk 3'ten düşmez; 3 ya da daha çok varsa en çok 3. sıradaki aday düşer.
- **Tavan:** puan en çok 0,9. Hiçbir aday otomatik düzeltme yapmaz.
- **Yasaklar:** öneri yasağı motorda üretilen biçimlere de uygulanır. Küfür bayraklı kök çekilmez.
- **Yazım hatası koruması değişmedi.** Üretilen biçim "bilinen kelime" sayılmaz.

## Ayar (yalnız dev)

İlk sürümün ızgarası:

| Parametre | Denenen değerler |
|---|---|
| `knownBonus` | 0 / 0,2 / 0,4 / 0,5 |
| `oovMargin` | −0,5 … 0,5 |
| `minChainLogProb` | −3,5 / −4 / −4,5 |
| `priorWeight` | 0,02 … 0,5 |
| `slope` | 0,04 / 0,06 |
| `perStem` | 4 / 12 |

`minChainLogProb` −4,5 dev'de yazım hatası ilk-3'ü 3,4 puan düşürüyor; bu yüzden −4'te kaldı.

Boşluk doldurma sürümünde `knownBonus` kaldırıldı, çünkü bilinen biçim artık önerilmiyor. Dev'de
`oovMargin` ve `perStem` olay sayılarıyla seçildi:

| Ayar | dev erişim | kazanılan / kaybedilen | değişen olay |
|---|---|---|---|
| oovMargin 0, perStem 8 | 21/34 | 19 / 11 | 479 |
| oovMargin 0,2, perStem 8 | 21/34 | 19 / 10 | 450 |
| oovMargin 0,3, perStem 8 | 20/34 | 18 / 9 | 435 |
| oovMargin 0,4, perStem 8 | 20/34 | 18 / 9 | 426 |
| **oovMargin 0,3, perStem 4** | 20/34 | **16 / 6** | 365 |
| oovMargin 0,4, perStem 4 | 19/34 | 14 / 6 | 352 |

Seçilen ayar `oovMargin` 0,3, `perStem` 4. Bu ayar dev'de kuralı payla tutturan, en az değişiklik
yapan ve erişimi en az kaybettiren ayar. Bunun bedeli dev OOV erişiminin 21/34'ten 20/34'e (%58,8)
inmesi.

## Ölçüm

### Seed-v0 (CI ile aynı komut; test yarısı son yapılandırmayla bir kez)

| Ölçü | dev önce | dev sonra | Δ | test önce | test sonra | Δ |
|---|---|---|---|---|---|---|
| İlk-3 @0 | %21,4 | %21,4 | +0,0 | %19,6 | %19,6 | +0,0 |
| İlk-3 @1 | %31,2 | %31,2 | +0,0 | %29,8 | %29,8 | +0,0 |
| İlk-3 @2 | %39,0 | %39,0 | +0,0 | %38,0 | %38,0 | +0,0 |
| İlk-3 @3 | %53,0 | %53,0 | +0,0 | %51,4 | %51,4 | +0,0 |
| KSR | %40,3 | %40,6 | +0,3 | %39,3 | %39,7 | +0,4 |
| Aksan düzeltme | %91,7 | %91,7 | +0,0 | %89,1 | %89,1 | +0,0 |
| Yanlış oto. düzeltme | %0,2 | %0,2 | +0,0 | %0,0 | %0,0 | +0,0 |
| Yazım hatası ilk-3 | %79,3 | %79,3 | +0,0 | %64,3 | %64,3 | +0,0 |
| "ek" İlk-3 @3 | %43,8 | %43,8 | +0,0 | %39,3 | %39,3 | +0,0 |
| "ek" KSR | %36,2 | %36,9 | +0,7 | %35,4 | %35,4 | +0,0 |

Testin ilk sürümü (47cd8284) de bir kez çalıştırılmıştı: @3 −0,4, KSR +0,5, "ek" @3 +1,2. Test
sayıları ayar için kullanılmadı.

### Olay düzeyinde karşılaştırma (önek olayları, ek zinciri yok → var)

Olay, yazılan boş olmayan, kelime sonu olmayan her öneri isteğidir. "Kazanılan" olay: hedef
kelime ilk 3'e girdi. "Kaybedilen" olay: hedef kelime ilk 3'ten çıktı.

| Sürüm | Bölüm | Olay | İlk 3 değişen | Kazanılan | Kaybedilen |
|---|---|---|---|---|---|
| 47cd8284 (yeniden sıralama) | dev | 3 590 | 859 (%23,9) | 44 | 47 |
| 47cd8284 | test | 3 937 | 922 (%23,4) | 60 | 61 |
| 47cd8284 | tümü | 7 527 | 1 781 (%23,7) | 104 | 108 |
| **boşluk doldurma** | dev | 3 590 | 365 (%10,2) | 16 | 6 |
| **boşluk doldurma** | test | 3 937 | 390 (%9,9) | 27 | 8 |
| **boşluk doldurma** | tümü | 7 527 | 755 (%10,0) | **43** | **14** |

Kaybedilen olayların hepsi aynı türden: temel 3. adayın yerini, aynı kökün daha olası görünen OOV
biçimi alıyor. Örnekler:

| Yazılan | Hedef | Ek zinciri yok | Ek zinciri var |
|---|---|---|---|
| kreş | kreşi | kreşe, kreş, kreşi | kreşe, kreşte, kreş |
| link | link | linke, linki, link | linke, linki, linkte |
| okumad | okumadan | …, okumadan | …, okumadı |
| masalın | masalını | …, masalını | …, maşalının |

CI artık bu tabloyu her çalıştırmada üretiyor ("Ek zinciri olay karşılaştırması" adımı). Kaybedilen
olay kazanılanın yarısını aşarsa uyarı yazıyor, CI'ı kırmıyor.

Hedef sözcüğün sınıfına göre (en az 4 harf):

| Sınıf | Kelime | önce @3 / sonra isabet / hiç | sonra @3 / sonra isabet / hiç |
|---|---|---|---|
| dev, sözlükte | 419 | 246 / 168 / 5 | 246 / 168 / 5 |
| dev, yalnız dil modelinde | 49 | 16 / 31 / 2 | 16 / 31 / 2 |
| dev, hiçbir yerde (OOV) | 34 | 4 / 14 / 16 | 4 / 21 / 9 |
| test, sözlükte | 447 | 259 / 181 / 7 | 259 / 180 / 8 |
| test, yalnız dil modelinde | 44 | 13 / 30 / 1 | 13 / 30 / 1 |
| test, OOV | 46 | 4 / 17 / 25 | 4 / 29 / 13 |

Sütunlar:
- "@3": hedef üçüncü harfte ilk 3'te.
- "sonra isabet": hedef son harften önce bir yerde ilk 3'te.
- "hiç": hedef son harften önce hiç ilk 3'te değil.

Son harften önce OOV erişimi dev'de 12/34'ten **20/34'e** (%58,8), testte 19/46'dan **32/46'ya**
(%69,6) çıktı. Bu sayım, "sonra isabet" sütununa göre daha katıdır.

Dev'de hâlâ ulaşılamayan OOV sözcükler ve nedenleri:

| Sözcük | Neden |
|---|---|
| annemleri | "annemler" kök değil |
| copunu | "çöp" baskın |
| kutunu, korunu | P2sg+Acc, bilinen rakiplere karşı olasılığı düşük |
| inemedi | kök 2 harf |
| Laptopumu | Zemberek yumuşama veriyor |
| Pantolonumu | yazılan yazım hatası |
| Nişantaşı'na | bileşik P3sg: 'ya üretiliyor, 'na değil |

### Korpus ölçümü (ayrılmış dilim, `suffix_corpus_eval.py`)

`corpus_false_ac.py` ile aynı bölme kullanıldı: her 10. satır ayrıldı (5 058 satır). Dil modeli ve
zincir tablosu ayrılan satırlar olmadan derlendi (`build_suffix_chains.py --holdout-every 10`:
45 396 cümle, 1 198 zincir). Hedefler, hem sözlükte hem eğitim dil modelinde olmayan, kökü bilinen
1 087 sözcük (1 045 ayrı biçim). Her biri önceki iki sözcükle, harf harf soruldu.

| Yapılandırma | Son harften önce erişim | KSR |
|---|---|---|
| ek zinciri yok | 0/1 087 = %0,0 | %0,0 |
| ek zinciri, boşluk doldurma (son ayar) | **573/1 087 = %52,7** | **%9,2** |
| ek zinciri, ilk sürüm (47cd8284) | 586/1 087 = %53,9 | %9,6 |

Ulaşılan örnekler: `duracağım@5`, `kızacak@5`, `saçlarında@9`, `beğensin@6`, `Macide'nin@6`.
Ulaşılamayanlar: `bazan`, `candarma` (eski yazım), `gönderdiğin`, `gezdiriyordum`, `görmeliydin`
(zincir seyrek ya da 4 harften uzun ek dizisi).

Kök tablosunun öznitelikleri tüm korpustan seçildi: bir kaydın birden çok Zemberek kaydı varsa
korpusta kullanılanı alındı. Bu küçük bir sızıntıdır. Zincir sayıları ise ayrılmış dilim olmadan
sayıldı.

### Zemberek geçerlilik örneği

Ayrılmış korpus diliminden rastgele 1 500 satır (tohum 6) sözcük sözcük, harf harf yazıldı
(30 987 istek; motor CI yapılandırmasında). İlk 3'te çıkan, sözlükte de dil modelinde de olmayan
ve yazılanın kendisi olmayan biçimler toplandı: 1 874 ayrı biçim (son ayar). Bunlardan rastgele 600'ü
Zemberek'e soruldu (`SuffixChains validate`).

| Örnek | Geçerli | Oran |
|---|---|---|
| Sürüm | Örnek | Geçerli | Oran |
|---|---|---|---|
| boşluk doldurma | 600 | 596 | **%99,3** |
| 47cd8284 | 600 | 595 | %99,2 |

Son ayarda geçersizler: `giderki`, `gitsiniz`, `kocaki`, `şanki`.

İlk denemede yazılanın kendisi de sayılmıştı (öneri çubuğu yazılanı gösterir, ör. "Ada'"). O
sayımla oran %96,5 idi.

### Kapılar

| Kapı | Sonuç |
|---|---|
| `eval-gate.py` (seed-v0 tümü, `--suffix`) | geçti. @3 %52,2, KSR %40,1, aksan %90,3, yanlış oto. düzeltme %0,1 |
| `personal-gate.py` | geçti. Kişisel set KSR farkı +11,8 (ek zinciri olmadan +12,2; eşik +8) |
| `corpus_false_ac.py --context` | geçti. %0,05 / bağlamlı %0,06; ek zinciri olmadan da aynı (11 ve 13 sözcük) |
| Motor testleri (`:engine:test`) | geçti |
| Ölçüm aracı testleri (50 test) | geçti |

Kapısız `typo-otomatik` yapılandırması (yazım hatası oto. düzeltmesi açık) ek zinciriyle 98/100
sözcük, ek zinciri olmadan 99/101 sözcük. İlk sürüm 96 veriyordu: orada yeniden sıralanan bilinen
biçimler, yazım hatası adayının oto. düzeltmesini gizliyordu.

`personal-gate.py` bir uyarı veriyor: "ilk kez İlk-3 @0 farkı −0,9". Bu uyarı ek zinciri olmadan
da aynı değerle çıkıyor.

Boş kişisel depoda (kisisel-v0, öğrenme kapalı) 13 530 olaydan 1 876'sının öneri listesi değişti
(ilk sürümde 4 013). Değişiklikler yalnız eklenen üretilmiş biçimler. Otomatik düzeltme farkı 0, sonraki kelime
önerisi farkı 0.

### Boyut, bellek, gecikme

| Ölçü | Değer | Hedef |
|---|---|---|
| `suffix-tr.kbs` | 6 506 bayt | ≤ 100 KB (CI eşiği 102 400 bayt) |
| Yükleme (masaüstü JVM, sınıf yükleme dahil) | 23 ms | ≤ 30 ms |
| Yığın artışı | ~55 KB | ≤ 0,5 MB |
| Kaynak gecikmesi (19 163 önek, `SuffixTool bench`) | p50 33 µs, p95 107 µs, p99 156 µs | — |
| Motor p95, dönüşümlü 3 çift (kapalı/açık) | 0,93/0,89 · 0,92/1,10 · 1,00/1,02 ms | ≤ 1,5 ms ve ≤ +0,2 ms |

Ölçüm aracının p95'i bu makinede çalıştırmadan çalıştırmaya 0,8–1,2 ms oynuyor. Dönüşümlü
ölçümde farklar −0,04, +0,18 ve +0,02 ms çıktı; ortalama +0,05 ms. p50 yaklaşık 0,05 ms arttı.
İkinci çift sınıra yakın, bu yüzden sonuç gürültüye duyarlı.

## Birleştirme kuralı (Fable, adım 6 (b))

| Kural | dev | test | tümü | Durum |
|---|---|---|---|---|
| Kaybedilen olay ≤ kazanılanın yarısı | 6 ≤ 8 | 8 ≤ 13,5 | **14 ≤ 21,5** | tuttu |
| Genel @3 ≥ temel − 0,1 | %53,0 = %53,0 | %51,4 = %51,4 | — | tuttu |
| Genel KSR ≥ temel | %40,6 ≥ %40,3 | %39,7 ≥ %39,3 | — | tuttu |
| Korpus OOV erişimi ≥ %40 | — | — | %52,7 | tuttu |
| Geçerlilik ≥ %95, kapılar, boyut, gecikme | %99,3; kapılar geçti; 6,5 KB; +0,05 ms | — | — | tuttu (gecikme gürültülü) |

## Hedefler (ilk spesifikasyon)

| Hedef | dev | test | Durum |
|---|---|---|---|
| "ek" İlk-3 @3 ≥ +3 puan | +0,0 | +0,0 | **ertelendi**: bir sonraki test seti (daha çok OOV çekim) |
| "ek" KSR ≥ +3 puan | +0,7 | +0,0 | **ertelendi**: aynı |
| Genel KSR ≥ +1,0 | +0,3 | +0,4 | **ertelendi**: aynı |
| OOV erişimi (son harften önce) ≥ %60 | %58,8 (20/34) | %69,6 (32/46) | tuttu (dev+test 52/80 = %65,0; dev tek başına %60'ın altında) |
| Genel @3 en çok −0,3 | +0,0 | +0,0 | tuttu |
| @0, @1 ±0,3 | 0,0 / 0,0 | 0,0 / 0,0 | tuttu |
| Aksan, yanlış oto. düzeltme (seed + korpus), yazım hatası ilk-3 değişmez | değişmedi | değişmedi | tuttu |
| Kişisel öğrenme kapısı geçer; boş depoda yalnız yeni adaylar | geçti; oto. düzeltme ve sonraki kelime aynı | — | tuttu |
| Üretilen OOV biçimlerin ≥ %95'i Zemberek'e göre geçerli (≥ 500 örnek) | %99,3 (600) | — | tuttu |
| p95 ≤ 1,5 ms ve ≤ +0,2 ms | +0,05 ms ortalama | — | tuttu (ölçüm gürültülü) |
| Varlık ≤ 100 KB | 6,5 KB | — | tuttu |
| Yığın ≤ 0,5 MB, yükleme ≤ 30 ms | 55 KB, 23 ms | — | tuttu |

Ertelenen hedefler neden seed-v0'da ölçülemiyor:
- Seed-v0'daki "ek" etiketi çoğunlukla sözlükte zaten olan çekimlerden oluşuyor; bu sette 86 dev
  sözcük var.
- Bu çekimlerin sırası kararla artık değiştirilmiyor.
- OOV çekim sayısı az: dev'de 34, testte 46.

## Riskler ve doğrulanmayanlar

- **Uygulama derlenmedi.** Bu ortamda Android SDK yok. `KBoardEngineLoader`,
  `KBoardSuggestions` ve `app/build.gradle.kts` değişiklikleri küçük tutuldu ve yalnız var olan
  API'ler kullanıldı, ama derleyiciden geçmedi. CI'daki `android` işi doğrulayacak.
- **Lisans.** `stems-tr.tsv` Apache-2.0 olarak işaretlendi: öznitelikler Zemberek kök
  sözlüğünden türetildi; atıf `NOTICE` dosyasında. Kelimeleri `main_tr.dict` ile kesiştiği için
  sözlük lisansının ("doğrulanacak") durumu bu tabloyu da ilgilendiriyor. `chains-tr.tsv` CC0-1.0
  kalıyor.
- **Korpus ölçümü CI'da yok.** Ayrılmış zincir tablosu Zemberek gerektiriyor. CI yalnız sevk
  edilen modelle seed, kişisel ve korpus yanlış oto. düzeltme adımlarını çalıştırıyor.
- **Yuva sınırı motor düzeyinde.** Yeni bir `GapFillSource` arayüzü eklendi. `Query` ve ölçüm
  protokolü değişmedi. Uygulamadaki `SuggestionEngine` de aynı kuralı uyguluyor.
- **Dev OOV erişimi %58,8.** Bu, ilk spesifikasyondaki %60'ın altında; dev ve test birlikte %65.
- Kesmeli bileşik özel isimler yanlış ek alıyor: "Nişantaşı'" yazılınca "Nişantaşı'na" değil,
  "Nişantaşı'ya" öneriliyor. Bileşik P3sg özniteliği tabloda yok.
