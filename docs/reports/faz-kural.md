# Yazım kuralları, PR A: yanlış yazım listesi ve bitişik soru eki (otomatik düzeltme)

Tarih: 2026-11-13 · Dal: `kural` (`suffix` dalı, PR #41 üzerine; #41 henüz birleşmedi) · Kapsam:
motor (`engine/`), veri, ölçüm aracı (`eval/`), `app/` bağlantısı ve CI. Karar: Fable, "spelling
rules PR A".

**Özet.** Klavye artık iki tür yazım kuralı hatasını boşlukta düzeltiyor:

1. **Yanlış yazım listesi.** Elle derlenmiş 138 kayıt var ("yanlız" → "yalnız", "herşey" → "her
   şey", "farkettim" → "fark ettim"); 68'inin doğru biçimi iki kelime. Liste, sözlükte olan
   yanlışları da düzeltiyor. 82 kayıt "kök" işaretli; bunların çekimleri de düzeltiliyor
   ("herkeze" → "herkese").
2. **Bitişik uzun soru eki.** "geliyormusun" → "geliyor musun" düzeltiliyor. Yalın "mi" bu kurala
   hiç girmiyor.

Bazı düzeltmeler iki kelimeye çıkıyor. HeliBoard bunları tek metin olarak yazıyor. Kullanıcı
geçmişine kelime kelime ekliyor. Geri almada da metnin tamamını siliyor. Bu kod okunarak
doğrulandı; cihazda denenmedi.

| Ölçü (kural-v0) | Hedef | dev | test |
|---|---|---|---|
| Tuzak vakalarda otomatik düzeltme | 0 | **0** (0/121 kelime, 32 vaka) | **0** (0/108 kelime, 32 vaka) |
| Kural setinde otomatik düzeltme kesinliği | ≥ %99 | **%100** (62/62) | **%98,4** (60/61) ✗ |
| Yanlış yazım listesi duyarlılığı | ≥ %95 | **%100** (48/48) | **%100** (45/45) |
| Bitişik uzun soru eki duyarlılığı | ≥ %85 | **%93,3** (14/15) | **%100** (15/15) |
| Korpusta yanlış oto. düzeltme (iki kip) | ≤ %0,3; kurallardan en çok +0,01 puan | — | %0,10 / %0,11 · ham fark **+0,051 puan** (bkz. "Korpus") |
| seed-v0 @0/@1/@3/KSR, aksan, yazım hatası ilk 3 | 0,2 puandan çok düşüş yok | düşüş yok | düşüş yok |
| Kişisel eşik; p95 ≤ 1,5 ms | geçmeli | geçiyor; seed-v0 p95 ~1,0–1,1 ms | |

Hedeflerden ikisi tam tutmuyor. Her ikisi için de karar Fable'ın.

- **Test kesinliği (%98,4).** Tek hata yeni kurallardan gelmiyor. Daha önce de olan ayrı yazılmış
  soru eki uyumundan geliyor (`DeasciifySource.questionParticle`): "orijinal mi" → "mı". Kuralların
  kendi yaptığı düzeltmelerin hepsi doğru: dev'de 62/62, testte 60/60. Bkz. "Test sonucu".
- **Korpus farkı (+0,051 puan).** Ham farkın tamamı 11 sözcükten geliyor. Bunlar korpusun
  kendisinde TDK'ya göre yanlış yazılmış sözcükler ("birşey", "sağol"); düzeltme doğru. Doğru
  yazılmış korpus sözcüklerinde yeni yanlış düzeltme yok (+0,000 puan). CI eşiği bu ikinci sayıya
  uygulanıyor. Bu bir ölçüm tanımı değişikliği olduğu için Fable onayına sunuluyor.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Yanlış yazım listesi (CC0, 138 kayıt) | `engine/src/main/resources/io/github/kboard/engine/rules/yazim-yanlislari-tr.tsv` |
| Kural kaynağı | `engine/.../rules/SpellingRuleSource.kt` (`MisspellingList`, `SpellingRuleSource`) |
| Motor | `Api.kt`: `RuleCorrector`, `Source.RULE`. `SuggestionEngine`: kuralın düzeltmesi ilk yuvaya geçer ve otomatik düzeltme olur |
| Yardımcılar | `DeasciifySource.hasFoldForm`, `isUserWord`; `FRONT_HARMONY_EXCEPTIONS` artık açık. `NgramSource.contains`, `containsFold` |
| CLI | `EvalCli`: kurallar `--deasciify` ile varsayılan olarak açık; `--no-rules`, `--no-rule-list`, `--no-rule-particle`, `--rules <tsv>`; yeni `revert` işlemi |
| Uygulama | `KBoardSuggestions`: kural kaynağı aksan düzelticiyle birlikte açılıyor; `noteRevert`. `InputLogic.revertCommit`: `// KBOARD:` satırı |
| Test seti | `eval/testset/kural-v0.jsonl` (196 vaka; yapay zekâ ile üretildi) |
| Ölçüm aracı | `testset.align` / `Case.aligned_targets`: tek yazılan kelime iki hedef kelimeye hizalanabiliyor. `simulate`: iki kelimelik hedef bağlamda iki kelime. `metrics`: `autocorrect_precision`, `fix_recall` |
| Eşikler | `.github/ci/kural-gate.py` (yeni). `data/tools/corpus_false_ac.py --compare yeni:taban=fark`. `ci.yml`: kural adımı, korpus adımında `kuralsiz` çalıştırması ve fark eşiği |
| Dil modeli | `buildNgramModel` ve `corpus_false_ac.py`, kural-v0'ı da sızıntı süzgecine ekliyor |
| Testler | `engine/src/test/.../rules/SpellingRuleSourceTest.kt` (8 test). `eval/tests`: hizalama ve iki kelimelik hedef. `app/src/test/.../KBoardSuggestionsTest.kt`: iki kelimelik düzeltme, kişisel sözlük ve geri alma (Robolectric; CI'da çalışır, burada SDK yok) |

## Kurallar

### Yanlış yazım listesi

Satır biçimi: `yanlış<TAB>doğru[<TAB>kök]`. Liste elle derlendi. Yayımlanmış bir liste
kopyalanmadı; doğru biçimler TDK Yazım Kılavuzu'na göre yazıldı. Yalnız tek okunuşlu yanlışlar
girdi. "birde" (saat birde), "oda", "bende", "hala" girmedi.

Liste şu durumlarda düzeltir:

1. **Kaydın kendisi yazıldıysa.** Sözlükte olsa da düzeltilir ("yanlız", "herşey", "yada",
   "şuan", "pekçok" `main_tr`'de var).
2. **ASCII ya da eksik aksanla yazıldıysa** ("hersey", "yanliz"). İki koşul gerekir:
   - Yazılan kendisi bir kelime olmamalı. "ambulans" ambülans kaydının katlanmışıdır ama doğru
     kelimedir; dokunulmaz.
   - Aksan düzeltici yazılanı listede olmayan başka bir kelimeye götürmemeli. "taktirdin"
     aslında "taktırdın" olabilir (seed-v0 aksan-0061); dokunulmaz.
3. **Kök kaydının çekimiyse** ("herşeyi" → "her şeyi", "herkezde" → "herkeste"). Düzeltilmiş
   çekim sözlükte ya da dil modelinde olmalı. Gerekirse aksan ve d/t, c/ç uyumu denenir. Çekim
   hiçbir yerde yoksa iki koşulla ek olduğu gibi taşınır ("eşortmanını" → "eşofmanını"): yazılan
   da hiçbir yerde olmamalı ve yanlış kök aynen yazılmış olmalı. Yazılan bilinen bir kelimeyse
   ("müsadere", "müsademe" müsade kökü gibi başlıyor) yalnız çekim biliniyorsa düzeltilir. Bu
   yüzden bu ikisine dokunulmaz.

Dev aşamasında iki kayıt çıkarıldı:

- "taktir": ASCII yazımda "taktır-" (taktırmak) ile çakışıyor.
- "küsür": ASCII yazımı "kusur" ile çakışıyor.

Dev aşamasında iki kayıt eklendi: "biliyormusun" ve "iyimisin". İkisi de korpusta geçtiği için dil
modelinin sözcük listesinde var. Bu yüzden soru eki kuralı bunlara dokunmaz (kural: yazılan dil
modelinde olmamalı). Bu ikisi, dil modeli sözcük listesinde bitişik soru ekli gerçek yanlış
olarak bulunan yalnız iki kelime. Diğer 8 eşleşme doğru kelime: adamıydım, resmiydi,
devamıdır...

Fable'ın gözden geçirmesi için üç grup ayrıca işaretlendi:

- **"değilmi", "varmı", "yokmu".** Yalın "mi"yi bitişik taşıyan ama başka okunuşu olmayan üç
  sözcük kalıbı. Soru eki kuralı değil, listede tek tek yazılmış kayıt.
- **"yinede", "hemde", "belkide".** Bitişik "de" taşıyan, başka okunuşu olmayan kayıtlar
  (de/da bölümü PR B'de, yalnız öneri olarak gelecek).
- **"sağol" (kök).** Sohbet dilinde çok yaygın. TDK'ya göre "sağ ol".

### Bitişik uzun soru eki

Kural Fable'ın koşullarını aynen uyguluyor:

- **Yalnız kişi ya da zaman ekli biçimler.** misin, misiniz, miyim, miyiz, midir,
  miydi(m/n/k/niz), miymiş ve dört ünlü uyumu varyantları. Yalın "mi" asla.
- **Yazılan hiçbir yerde olmamalı.** Sözlükte, dil modelinde ve kişisel modelde (2+ kullanım)
  aynen olmamalı. Katlanmış biçimi sözlükte ya da dil modelinde olan da düzeltilmez: "gormusun"
  aslında "görmüşün" olabilir.
- **Sol parça yazıldığı gibi bir kelime olmalı.** En az 3 harfli bir sözlük ya da dil modeli
  kelimesi. Dev aşamasında daraltıldı: aksansız sol parçanın ("gorme") aksanlı okunuşu
  denenmiyor, çünkü "gormemisin" "görmemişin" de olabilir. Kural gevşetilmedi, daraltıldı.
- **Dört yollu ünlü uyumu.** `front-harmony-tr.txt` istisnaları kullanılıyor ("normalmisin" →
  "normal misin"). ASCII'de yalnız kaybolan aksan geri geliyor ("yapacakmisin" → "yapacak
  mısın"). Uyumsuz ek düzeltilmez ("gelirmusun").
- **Ek koşul: sol parça + ekin ilk iki harfi bir kelimeyse dokunulmaz.** Bu, Fable'ın korpusta
  bulduğu yanlış eşleşmeyi kapatıyor: "adamıydım" = "adamı" + "ydım".

### Kullanıcı istisnaları

Şu durumlarda düzeltme yapılmaz:

- Kelime kişisel sözlükte.
- Kişisel model kelimeyi en az 2 kez öğrenmiş.
- Kullanıcı aynı düzeltmeyi geri almış. `InputLogic.revertCommit` → `KBoardSuggestions.noteRevert`
  çağrılır. Bu bellek oturum içindir; uygulama yeniden başlayınca silinir.

Uygulamada bir istisna daha var. HeliBoard yazılan kelimeyi kişisel sözlükte, kişilerde ya da
kullanıcı geçmişinde bulduysa karar eskisi gibi HeliBoard'da kalır (`USER_SOURCES`). Kullanıcı
geçmişinde 1 kullanım yeter; bu, şartnamenin "2+ kullanım" koşulundan sıkı.

## İki kelimelik düzeltme (uygulama yolu)

HeliBoard'un kodu okundu:

- **Yazma.** `InputLogic.commitCurrentAutoCorrection` → `commitChosenWord`, düzeltmeyi
  `RichInputConnection.commitText` ile tek metin olarak yazıyor. İçindeki boşluk için ayrı bir işlem
  yok, metin olduğu gibi giriyor.
- **Kullanıcı geçmişi.** `DictionaryFacilitatorImpl.addToUserHistory` metni boşluktan bölüyor
  (`splitOnWhitespace`). Her kelimeyi bir öncekinin bağlamıyla ekliyor; kişisel öğrenme kancası
  da bu döngüde. Kişisel sözlüğe otomatik ekleme yalnız tek kelimede yapılıyor.
- **Geri alma.** `revertCommit`, `committedWord.length()` + ayraç kadar siliyor ve yazılanı geri
  koyuyor. Boşluklu düzeltme de tamamen geri alınıyor.
- **Sonraki kelime bağlamı.** `getNgramContextFromNthPreviousWord` metinden okunuyor. "her şey "
  sonrasında önceki kelimeler "her", "şey" oluyor.

Bilinen eksik: geri almadaki `unlearnFromUserHistory("her şey")` bekleyen kelimeleri tek tek
tuttuğu için bulamıyor. "her" ve "şey" geçmişte ve kişisel modelde kalıyor. İkisi de doğru kelime
olduğu için zararsız. Cihazda ya da emülatörde **denenmedi**. Robolectric testi CI'da
çalışacak.

## Test seti: kural-v0

`eval/testset/kural-v0.jsonl`: 196 vaka. Yapay zekâ ile üretildi (Claude), seed-v0 ve kisisel-v0
okunmadan yazıldı. İnsan gözden geçirmedi. Lisans CC0-1.0. Kategori `kural`; dev/test ayrımı
kimliğe göre (crc32).

| Etiket | Ne | dev | test |
|---|---|---|---|
| `kural_liste` | listedeki bir yanlış, cümlede tek hata | 48 | 45 |
| `kural_soru` | bitişik uzun soru eki | 15 | 15 |
| `kural_tuzak` | değişmemesi gereken doğru cümle | 32 | 32 |
| `kural_dedaki` | de/da/ki (PR B için; 9 hata, 4 tuzak) | 5 | 8 |

Tuzaklarda şunlar var:

- Fable'ın örnekleri: evde kaldım, bende kalsın, sendeki, kendimi, telefonumu, eski, sanki, saat
  birde.
- Ek gibi biten doğru kelimeler: adamıydım, kalemiydi, devamıdır, resmiydi, işadamıyım.
- Katlama çakışmaları: ambulans, kusura, fasulye, unvan, şoför.
- Kök çakışmaları: herkesin, taktırmak, müsademe.
- Doğru ayrı yazımlar: her şey, ya da, şu an, ben de.
- -ki ile biten doğru kelimeler: halbuki, mademki, dünkü, yarınki.

İki kelimelik hedef için yükleyici genişletildi: `input` ile `text` artık yalnız şu durumda farklı
sayıda kelime içerebilir. Yazılan her kelime 1–2 ardışık hedef kelimeye hizalanmalı; birleşik
hedefin katlanmış uzaklığı en çok 2 olmalı. Hizalama, düzenleme uzaklığı en küçük olan seçilerek
yapılıyor. Eşit sayılı setlerde (seed-v0, kisisel-v0) hiçbir şey değişmiyor.

## Test sonucu

Ayar yalnız dev'de yapıldı. Test yarısı tek bir kez çalıştırıldı: tuzak 0/108, kesinlik 60/61,
liste 45/45, soru eki 15/15.

Bu çalıştırmadan sonra iki değişiklik yapıldı. İkisi de test yarısından değil, başka
kaynaklardan bulundu:

- seed-v0 dev'deki aksan-0061'den: "taktir" ve "küsür" çıkarıldı, ASCII koşulu eklendi.
- Korpusun aksansız taramasından ("gormusun"): soru ekinde sol parça artık yazıldığı gibi kelime
  olmalı.

Değişikliklerden sonra test yeniden çalıştırıldı; sonuçlar aynı çıktı.

Testteki tek kesinlik hatası kural-liste-063: "Bu orjinal mi, çakma mı?". Bağlamdaki kelime
"orijinal" olduğu için önceki sürümden kalan ayrı soru eki uyumu "mi"yi "mı" yapıyor. "orijinal"
de "normal", "ideal" gibi ince ek alıyor ve `front-harmony-tr.txt`'e girmeli. Hata testte
bulunduğu için bu PR'da **düzeltilmedi**; ayrı bir değişiklik olarak öneriliyor.

Kurallar kapalıyken (`--no-rules`) kural setinin tamamında 3 otomatik düzeltme vardı, hiçbiri
doğru değildi:

- "Hersey" → "Herşey"
- "birsey" → "birşey"
- "mi" → "mı"

Liste ve soru eki duyarlılığı o durumda 0/93 ve 0/30. Kurallar açıkken tümünde kesinlik 122/123,
liste 93/93, soru eki 29/30.

Dev'de kaçan tek vaka kural-soru-024: "çantanmıydı". Sol parça "çantan" ne sözlükte ne dil
modelinde olduğu için kural bilerek düzeltmiyor.

de/da/ki vakalarına (PR B) PR A dokunmuyor: 9 hatanın hiçbiri düzeltilmedi, 4 tuzakta düzeltme
yok. Bu vakalar `kural` genel `fix_recall`'unu düşürüyor; liste ve soru eki ölçüleri ayrı
etiketlerden.

## Korpus

Ölçüm `data/tools/corpus_false_ac.py --context` ile yapıldı. Ayrılan dilim her 10. satır: 5 058
satır, 21 687 sözcük. Dil modeli bu dilim olmadan derlendi.

| Yapılandırma | PR #41 (önce) | Bu PR, kurallar kapalı | Bu PR |
|---|---|---|---|
| varsayılan | 11 (%0,051) | 11 (%0,051) | 22 (%0,101) |
| varsayılan + bağlam | 13 (%0,060) | 13 (%0,060) | 24 (%0,111) |
| typo-otomatik (eşiksiz, bilgi) | 98 (%0,45) | 98 | 109 (%0,50) |

Kuralların getirdiği 11 yeni düzeltmenin hepsi Common Voice'ta TDK'ya göre yanlış yazılmış bir
sözcük. Düzeltmelerin hepsi doğru:

| Korpusta yazılan → düzeltme | Varsayılan | Bağlamlı örnek | Açıklama |
|---|---|---|---|
| birşey / Birşey → bir şey | 3 | "Sana birşey", "Önemli birşey" | TDK: "bir şey" ayrı |
| Herşey → Her şey | 1 | "Herşey" | TDK: "her şey" ayrı |
| sağol / Sağol → sağ ol | 3 | "Yok sağol", "Çok sağol" | TDK: "sağ ol" ayrı |
| sağolun / Sağolun → sağ olun | 2 | "Sağolun sağolun" | "sağol" kökünün çekimi |
| farkeder → fark eder | 1 | "Ne farkeder" | TDK: "fark etmek" ayrı |
| sezeryan → sezaryen | 1 | "otuz liraya sezeryan" | TDK: "sezaryen" |

Doğru yazılmış korpus sözcüklerinde yeni yanlış düzeltme yok; giden de yok.

Ham fark +0,051 puan ve Fable'ın +0,01 puan sınırını aşıyor. Bu ölçüm korpustaki her sözcüğü doğru
sayıyor, ama yukarıdaki 11 sözcük doğru değil. CI eşiği (`--compare ...=0.0001`) bu yüzden
yazılanı listede olan farkları dışarıda bırakarak uygulanıyor. Ham fark her çalıştırmada
yazılıyor ve JSON'a da (`compare.delta`) yazılıyor. **Fable'ın onayı gerekiyor.**

Onaylanmazsa iki yol var:

- Liste o sözcüklerden arındırılır. Ama "herşey" ve "birşey" şartnamenin kendi örnekleri.
- Common Voice'taki bu satırlar ölçümden çıkarılır.

Ek denetimler bunlar dev sayıldı; eğitim verisi de içerdikleri için resmî ölçüm değiller:

- Tüm korpusun her ayrı sözcüğü, aksanlı ve aksansız yazılarak denendi.
- Sözlüğün 180 451 sözcüğü, aksanlı ve aksansız yazılarak denendi.

Kuralların değiştirdiği her sözcük listede bir yanlış ya da çekimi çıktı. Bu taramada iki hata
bulundu ve yukarıda anlatıldığı gibi kapatıldı: "Yanlızçam" → "Yalnızçam" doğru bir düzeltme;
"gormusun" → "gör müsün" yanlıştı, kapatıldı.

## seed-v0 farkları (önce PR #41 → sonra)

| Yarı | @0 | @1 | @3 | KSR | Aksan | Yanlış oto. düz. | Yazım hatası ilk 3 | Yazım hatası düzeltme |
|---|---|---|---|---|---|---|---|---|
| dev | 21,4 (0) | 31,2 (0) | 53,0 (0) | 40,6 (0) | 91,7 (0) | 0,2 (0) | 79,3 → 82,8 | 0 → 31,0 (9/29) |
| test | 19,6 (0) | 29,8 (0) | 51,4 (0) | 39,7 → 39,8 | 89,1 (0) | 0,0 (0) | 64,3 → 89,3 | 0 → 35,7 (10/28) |
| tümü | 20,5 (0) | 30,5 (0) | 52,2 (0) | 40,1 (+0,02) | 90,3 (0) | 0,1 (0) | 71,9 → 86,0 | 0 → 33,3 (19/57) |

Commit'i değişen 19 sözcüğün 19'u da hedefe gitti. Bunlar seed-v0'ın `yaygin_hata` vakaları:
Herkez, Yanlız, Orjinal, Laboratuara, Eşortmanını, Deyil... Liste seed-v0 okunmadan yazıldı. Ama
iki set de "yaygın yanlışlar" bilgisinden çıktığı için bu kazanç iyimser okunmalı.

`eval-gate.py`'nin bütün eşikleri geçiyor. Kişisel eşiğin hepsi geçiyor. "ilk kez @0 farkı"
uyarısı (hedef −0,5) PR #41'de −0,9'du, şimdi −1,1. Fark kurallardan değil, dil modelinden
geliyor: `--no-rules` ile de −1,1. kural-v0 sızıntı süzgecine eklenince 16 Common Voice ve 4
sohbet cümlesi eğitimden çıktı; 4 kelimeden kısa tuzak cümleleri alt dizi olarak eşleşiyor. Koruma
eşiği −1,5 ve geçiyor. Model boyutu 1 078 334 → 1 078 085 bayt.

Gecikme sessiz makinede üçer kez ölçüldü:

| Set | PR #41 p95 | Bu PR p95 |
|---|---|---|
| seed-v0 | 1,02–1,16 ms | 0,97–1,08 ms |
| kural-v0 (uzun kelimeler) | 1,70–1,91 ms | 1,77–2,13 ms |

Fark ölçüm gürültüsü içinde. 1,5 ms eşiği seed-v0 için.

## Riskler ve doğrulanmayanlar

- **İki kelimelik düzeltme cihazda denenmedi.** Yalnız kod okundu ve Robolectric testi yazıldı.
  Uygulama bu ortamda derlenmedi (Android SDK yok); CI derleyecek.
- **Korpus fark eşiğinin tanımı değişti.** Listedeki korpus yanlışları hariç tutuluyor; Fable
  onayı gerekiyor (yukarıda).
- **Test kesinliği %98,4.** Tek hata eski soru eki uyumundan ("orijinal mi"). Önerilen düzeltme:
  `front-harmony-tr.txt`'e "orijinal". Yapılmadı.
- **Geri alma belleği oturumluk.** Uygulama kapanınca silinir. Kullanıcı sözlüğe ekleyerek ya da
  kelimeyi 2+ kez kullanarak kalıcı istisna yapabilir. Ama düzeltilen kelime metne geçmediği için
  kişisel model onu öğrenemez.
- **Uygulamada HeliBoard kullanıcı geçmişi 1 kullanımda istisna.** Daha önce "herşey" yazmış bir
  kullanıcıda o kelime düzeltilmeyebilir (`USER_SOURCES`, değiştirilmedi).
- **Sohbet dilinde rahatsızlık.** "sağol", "farkettim", "değilmi" bilerek bitişik yazılabiliyor;
  düzeltme TDK'ya göre doğru ama istenmeyebilir. Geri alma ve kişisel sözlük bunu kapatıyor.
- **Test seti kural yazarıyla aynı kaynaktan.** Yapay zekâ üretti, insan gözden geçirmedi;
  duyarlılık sayıları iyimser.
- **`data/suffix/chains-tr.tsv` yeniden üretilmedi.** Zemberek gerekiyor; kural-v0 bu yüzden ek
  zinciri tablosunun sızıntı süzgecinde yok. Tablo yalnız soyut zincir sayıları içeriyor.
- **CI eşiği kural-v0'ın tamamında** (dev + test) uygulanıyor; hedef tablosu test yarısı içindir.
