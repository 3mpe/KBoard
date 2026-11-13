# Veriye dayalı ek zinciri tamamlaması (plan adım 6)

Tarih: 2026-11-13 · Dal: `suffix` (main d54d2f17 üzerine) · Kapsam: motor (`engine/`), veri
(`data/suffix/`), ölçüm, `app/` bağlantısı ve CI.

**Özet.** Bilinen bir kök (en az 3 harf) tamamen yazıldıktan sonra, kökün sınıfına göre korpustan
sayılmış ek zincirleri öneriliyor. Aynı model, kökü paylaşan sözlük ve dil modeli tamamlamalarını
da yeniden sıralıyor. Sonuç:

- Sözlükte de dil modelinde de olmayan çekimlere erişim belirgin biçimde arttı: seed dev'de 12/34'ten
  21/34'e, test yarısında 19/46'dan 32/46'ya, korpusun ayrılmış diliminde %0'dan %53,9'a.
- Seed setindeki "ek" etiketi ve genel KSR hedeflerine **ulaşılamadı**. Ayrıntı ve sayılar
  "Hedefler" bölümünde.
- Ayar dev'de yapıldı. Spesifikasyonun dediği gibi, korpus ölçüsü açıkça iyileştiği için
  ayara devam edilmedi; sayılar olduğu gibi raporlanıyor.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Zincir tablosu üretici (masaüstü, Zemberek) | `data/tools/build_suffix_chains.py`, `tools/zemberek-spike/.../SuffixChains.java` (`analyze`, `lexicon`, `validate` kipleri) |
| Zincir tablosu (depoda, CC0) | `data/suffix/chains-tr.tsv` (21,7 KB; N 411, P 17, V 830 zincir) |
| Kök sınıfı tablosu (depoda) | `data/suffix/stems-tr.tsv` (24 369 kök: N 22 932, V 1 301, NV 136) |
| Kök bayrakları | `DeasciifyIndex` bayrak baytının boş bitleri (`FLAG_STEM_*`); `DeasciifyTool build` 5. argüman. Dizin boyutu değişmedi |
| Uyum (tek uygulama) | `engine/.../morph/SuffixGenerator.kt`: `Stem`, `Partial`, `start`/`append`; yeni şablonlar `^Iyor`, `R`, `P`, `T`, `K`, `(s)`, `(ş)`, `(I)`, `C` |
| İkili model | `engine/.../morph/SuffixChainModel.kt` (`.kbs` sürüm 1; sınıf başına önek ağacı, alt ağaç üst sınırı) |
| Aday kaynağı | `engine/.../morph/SuffixChainSource.kt` (`morph-chain`, `Source.MORPHOLOGY`) |
| Araç | `engine/.../morph/SuffixTool.kt` (`build`, `check`, `bench`) |
| Derleme | `engine/build.gradle.kts`: `buildSuffixModel` → `build/kboard/suffix-tr.kbs`; `buildDeasciifyIndex` kök tablosunu okur |
| CLI | `EvalCli`: `--suffix <model.kbs>` (ortam `KBOARD_SUFFIX`), `--suffix-params anahtar=değer,...` |
| Korpus ölçümü | `data/tools/suffix_corpus_eval.py` |
| Uygulama | `KBoardEngineLoader` (`kboard/suffix-tr.kbs`, dizinle birlikte bağlanır), `KBoardSuggestions.setSuffix` + `rebuild()`, `app/build.gradle.kts` (`KBoardAssetsTask`) |
| CI | `ci.yml`: `buildSuffixModel`; seed, kişisel ve korpus adımlarında `--suffix`; APK'da varlık denetimi ve 100 KB boyut eşiği |
| Testler | `engine/src/test/.../morph/SuffixChainTest.kt` (7 test); motor testlerinin hepsi geçiyor |

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
- **Bilinen biçim** (sözlükte ya da dil modelinde) `knownBonus` = 0,2 alır. Bu, aynı kökü
  paylaşan tamamlamaları yeniden sıralar.
- **Bilinmeyen biçim** `oovMargin` = 0 alır ve yalnız log10 P(zincir) ≥ −4 ise önerilir.
- **Tavan:** puan en çok 0,9. Hiçbir aday otomatik düzeltme yapmaz.
- **Yasaklar:** öneri yasağı motorda üretilen biçimlere de uygulanır. Küfür bayraklı kök çekilmez.
- **Yazım hatası koruması değişmedi.** Üretilen biçim "bilinen kelime" sayılmaz.

## Ayar (yalnız dev)

Izgara aralıkları:

| Parametre | Denenen değerler |
|---|---|
| `knownBonus` | 0 / 0,2 / 0,4 / 0,5 |
| `oovMargin` | −0,5 … 0,5 |
| `minChainLogProb` | −3,5 / −4 / −4,5 |
| `priorWeight` | 0,02 … 0,5 |
| `slope` | 0,04 / 0,06 |
| `perStem` | 4 / 12 |

Seçilen değerler: `knownBonus` 0,2, `oovMargin` 0, `minChainLogProb` −4.

Seçimde göz önünde tutulan ödünleşimler:

- **`minChainLogProb` −4,5:** erişimi artırıyor (korpusta %58,3). Ama dev'de yazım hatası ilk-3'ü
  3,4 puan düşürüyor, çünkü boş kalan öneri yuvalarını dolduruyor.
- **`knownBonus` ≥ 0,4:** dev'de genel @3'ü 0,4–1,0 puan düşürüyor. Örnek: "yoğ" kökünün
  "yoğu", "yoğa" biçimleri "yoğurt"u çubuktan itiyor.
- **`oovMargin` −0,3:** dev'de erişimi 22/34'e çıkarıyor, ama "ek" etiketinde @3 kazancını
  +2,5'ten +1,3'e indiriyor.

## Ölçüm

### Seed-v0 (CI ile aynı komut; test yarısı bir kez, son yapılandırmayla)

| Ölçü | dev önce | dev sonra | Δ | test önce | test sonra | Δ |
|---|---|---|---|---|---|---|
| İlk-3 @0 | %21,4 | %21,4 | +0,0 | %19,6 | %19,6 | +0,0 |
| İlk-3 @1 | %31,2 | %31,2 | +0,0 | %29,8 | %29,8 | +0,0 |
| İlk-3 @2 | %39,0 | %39,0 | +0,0 | %38,0 | %38,0 | +0,0 |
| İlk-3 @3 | %53,0 | %53,2 | +0,2 | %51,4 | %51,0 | −0,4 |
| KSR | %40,3 | %40,6 | +0,3 | %39,4 | %39,9 | +0,5 |
| Aksan düzeltme | %91,7 | %91,7 | +0,0 | %89,1 | %89,1 | +0,0 |
| Yanlış oto. düzeltme | %0,2 | %0,2 | +0,0 | %0,0 | %0,0 | +0,0 |
| Yazım hatası ilk-3 | %79,3 | %79,3 | +0,0 | %64,3 | %64,3 | +0,0 |
| "ek" İlk-3 @3 | %43,8 | %46,2 | +2,5 | %39,3 | %40,5 | +1,2 |
| "ek" KSR | %36,2 | %38,1 | +1,9 | %35,4 | %36,1 | +0,7 |

Hedef sözcüğün sınıfına göre (en az 4 harf):

| Sınıf | Kelime | önce @3 / sonra isabet / hiç | sonra @3 / sonra isabet / hiç |
|---|---|---|---|
| dev, sözlükte | 419 | 246 / 168 / 5 | 247 / 167 / 5 |
| dev, yalnız dil modelinde | 49 | 16 / 31 / 2 | 16 / 31 / 2 |
| dev, hiçbir yerde (OOV) | 34 | 4 / 14 / 16 | 4 / 21 / 9 |
| test, sözlükte | 447 | 259 / 181 / 7 | 256 / 180 / 11 |
| test, yalnız dil modelinde | 44 | 13 / 30 / 1 | 14 / 29 / 1 |
| test, OOV | 46 | 4 / 17 / 25 | 4 / 29 / 13 |

Sütunlar: "@3" üçüncü harfte ilk 3'te, "sonra isabet" son harften önce bir yerde ilk 3'te, "hiç"
son harften önce hiç ilk 3'te değil. Son harften önce OOV erişimi: dev 12/34 → **21/34 (%61,8)**,
test 19/46 → **32/46 (%69,6)**.

Dev'de hâlâ ulaşılamayan OOV sözcükler ve nedenleri:

| Sözcük | Neden |
|---|---|
| annemleri | "annemler" kök değil |
| copunu | "çöp" baskın |
| kutunu, korunu | P2sg+Acc, bilinen rakiplere karşı olasılığı düşük |
| inemedi | kök 2 harf |
| Laptopumu | Zemberek yumuşama veriyor |
| Pantolonumu | yazılan yazım hatası |
| Doruk'u | 4. sırada |
| Nişantaşı'na | bileşik P3sg: 'ya üretiliyor, 'na değil |

### Korpus ölçümü (ayrılmış dilim, `suffix_corpus_eval.py`)

`corpus_false_ac.py` ile aynı bölme kullanıldı: her 10. satır ayrıldı (5 058 satır). Dil modeli ve
zincir tablosu ayrılan satırlar olmadan derlendi (`build_suffix_chains.py --holdout-every 10`:
45 396 cümle, 1 198 zincir). Hedefler, hem sözlükte hem eğitim dil modelinde olmayan, kökü bilinen
1 087 sözcük (1 045 ayrı biçim). Her biri önceki iki sözcükle, harf harf soruldu.

| Yapılandırma | Son harften önce erişim | KSR |
|---|---|---|
| ek zinciri yok | 0/1 087 = %0,0 | %0,0 |
| ek zinciri (son ayar) | **586/1 087 = %53,9** | **%9,6** |
| `oovMargin` −0,3, `minChainLogProb` −4,5 (dev'de yazım hatası −3,4) | 656/1 087 = %60,3 | %11,2 |

Ulaşılan örnekler: `duracağım@5`, `kızacak@5`, `saçlarında@9`, `beğensin@6`, `Macide'nin@6`.
Ulaşılamayanlar: `bazan`, `candarma` (eski yazım), `gönderdiğin`, `gezdiriyordum`, `görmeliydin`
(zincir seyrek ya da 4 harften uzun ek dizisi).

Kök tablosunun öznitelikleri tüm korpustan seçildi: bir kaydın birden çok Zemberek kaydı varsa
korpusta kullanılanı alındı. Bu küçük bir sızıntıdır. Zincir sayıları ise ayrılmış dilim olmadan
sayıldı.

### Zemberek geçerlilik örneği

Ayrılmış korpus diliminden rastgele 1 500 satır (tohum 6) sözcük sözcük, harf harf yazıldı
(30 987 istek; motor CI yapılandırmasında). İlk 3'te çıkan, sözlükte de dil modelinde de olmayan
ve yazılanın kendisi olmayan biçimler toplandı: 1 829 ayrı biçim. Bunlardan rastgele 600'ü
Zemberek'e soruldu (`SuffixChains validate`).

| Örnek | Geçerli | Oran |
|---|---|---|
| 600 | 595 | **%99,2** |

Geçersizler: `giderki`, `giderkine`, `gitsiniz`, `matki`, `şanki`.

İlk denemede yazılanın kendisi de sayılmıştı (öneri çubuğu yazılanı gösterir, ör. "Ada'"). O
sayımla oran %96,5 idi.

### Kapılar

| Kapı | Sonuç |
|---|---|
| `eval-gate.py` (seed-v0 tümü, `--suffix`) | geçti. @3 %52,1, KSR %40,2, aksan %90,3, yanlış oto. düzeltme %0,1 |
| `personal-gate.py` | geçti. Kişisel set KSR farkı +11,8 (öncesi +12,2; eşik +8) |
| `corpus_false_ac.py --context` | geçti. %0,05 / bağlamlı %0,06; ek zinciri olmadan da aynı (11 ve 13 sözcük) |
| Motor testleri (`:engine:test`) | geçti |
| Ölçüm aracı testleri (50 test) | geçti |

`personal-gate.py` bir uyarı veriyor: "ilk kez İlk-3 @0 farkı −0,9". Bu uyarı ek zinciri olmadan
da aynı değerle çıkıyor.

Boş kişisel depoda (kisisel-v0, öğrenme kapalı) 13 530 olaydan 4 013'ünün öneri listesi değişti.
Bunlar yeni ve yeniden sıralanmış ek biçimleri. Otomatik düzeltme farkı 0, sonraki kelime
önerisi farkı 0.

### Boyut, bellek, gecikme

| Ölçü | Değer | Hedef |
|---|---|---|
| `suffix-tr.kbs` | 6 506 bayt | ≤ 100 KB (CI eşiği 102 400 bayt) |
| Yükleme (masaüstü JVM, sınıf yükleme dahil) | 23 ms | ≤ 30 ms |
| Yığın artışı | ~55 KB | ≤ 0,5 MB |
| Kaynak gecikmesi (19 163 önek, `SuffixTool bench`) | p50 33 µs, p95 107 µs, p99 156 µs | — |
| Motor p95, dönüşümlü 3 çift (kapalı/açık) | 0,93/0,89 · 1,01/1,07 · 1,17/1,14 ms | ≤ 1,5 ms ve ≤ +0,2 ms |

Ölçüm aracının p95'i bu makinede çalıştırmadan çalıştırmaya 0,8–1,2 ms oynuyor. Dönüşümlü
ölçümde fark gürültü içinde kaldı: −0,04, +0,06 ve −0,03 ms. p50 yaklaşık 0,08 ms arttı.

## Hedefler

| Hedef | dev | test | Durum |
|---|---|---|---|
| "ek" İlk-3 @3 ≥ +3 puan | +2,5 | +1,2 | **tutmadı** |
| "ek" KSR ≥ +3 puan | +1,9 | +0,7 | **tutmadı** |
| OOV erişimi (son harften önce) ≥ %60 | %61,8 (21/34) | %69,6 (32/46) | tuttu |
| Genel KSR ≥ +1,0 | +0,3 | +0,5 | **tutmadı** |
| Genel @3 en çok −0,3 | +0,2 | −0,4 | dev tuttu, **test tutmadı** (−0,1 fazla) |
| @0, @1 ±0,3 | 0,0 / 0,0 | 0,0 / 0,0 | tuttu |
| Aksan, yanlış oto. düzeltme (seed + korpus), yazım hatası ilk-3 değişmez | değişmedi | değişmedi | tuttu |
| Kişisel öğrenme kapısı geçer; boş depoda yalnız yeni adaylar | geçti; oto. düzeltme ve sonraki kelime aynı | — | tuttu |
| Üretilen OOV biçimlerin ≥ %95'i Zemberek'e göre geçerli (≥ 500 örnek) | %99,2 (600) | — | tuttu |
| p95 ≤ 1,5 ms ve ≤ +0,2 ms | fark gürültü içinde | — | tuttu (ölçüm gürültülü) |
| Varlık ≤ 100 KB | 6,5 KB | — | tuttu |
| Yığın ≤ 0,5 MB, yükleme ≤ 30 ms | 55 KB, 23 ms | — | tuttu |

Seed hedeflerinin tutmamasının nedeni, kazanımın büyük kısmının seed setinin küçük OOV payında
kalması. Dev'de 34 OOV sözcük var. Bunlardan 9'u daha erken ilk 3'e girdi, ama ortalama 3–4 harf
erken girdi. "ek" etiketi ise çoğunlukla sözlükte zaten olan çekimlerden oluşuyor; orada yeniden
sıralama dil modelinin bağlamlı tahminini ancak az geçebiliyor. `knownBonus`'u artırmak @3'ü
düşürdü.

Önceki bir ayarda (`knownBonus` 0) kaynak kendi başına, 88 dev sözcükte hedefi temel sistemin ilk isabetinden daha erken üretiyor
(127 tuş). Yani yeniden sıralamada pay var. Ama bunu genel @3'ü bozmadan almanın yolu bulunamadı.

## Riskler ve doğrulanmayanlar

- **Uygulama derlenmedi.** Bu ortamda Android SDK yok. `KBoardEngineLoader`,
  `KBoardSuggestions` ve `app/build.gradle.kts` değişiklikleri küçük tutuldu ve yalnız var olan
  API'ler kullanıldı, ama derleyiciden geçmedi. CI'daki `android` işi doğrulayacak.
- **Lisans.** `stems-tr.tsv` öznitelikleri Zemberek kök sözlüğünden (Apache-2.0), kelimeleri
  `main_tr.dict`'ten geliyor; sözlük lisansı hâlâ "doğrulanacak". Tablo CC0 olarak işaretlendi
  (proje katkısı); bu görüş gözden geçirilmeli.
- **Korpus ölçümü CI'da yok.** Ayrılmış zincir tablosu Zemberek gerektiriyor. CI yalnız sevk
  edilen modelle seed, kişisel ve korpus yanlış oto. düzeltme adımlarını çalıştırıyor.
- **Seed test yarısında @3 −0,4.** Eşik −0,3. Kayıplar kısa köklerin (yoğ, yen) bilinen
  çekimlerinin uzun sözcükleri itmesinden geliyor.
- Kesmeli bileşik özel isimler yanlış ek alıyor: "Nişantaşı'" yazılınca "Nişantaşı'na" değil,
  "Nişantaşı'ya" öneriliyor. Bileşik P3sg özniteliği tabloda yok.
