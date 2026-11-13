# Yazım kuralları, PR A: yanlış yazım listesi ve bitişik soru eki (otomatik düzeltme)

Tarih: 2026-11-13 · Dal: `kural` (`main` üzerine: #41 ve #42 birleşti; ilk ölçümler PR #41 dalında yapıldı, 2. tur sonuçları `main` üzerinde aynı çıktı) · Kapsam:
motor (`engine/`), veri, ölçüm aracı (`eval/`), `app/` bağlantısı ve CI. Karar: Fable, "spelling
rules PR A".

**Özet.** Klavye artık iki tür yazım kuralı hatasını boşlukta düzeltiyor:

1. **Yanlış yazım listesi.** Elle derlenmiş 137 kayıt var ("yanlız" → "yalnız", "herşey" → "her
   şey", "farkettim" → "fark ettim"); 68'inin doğru biçimi iki kelime. Liste, sözlükte olan
   yanlışları da düzeltiyor. 78 kayıt "kök" işaretli; bunların çekimleri de düzeltiliyor
   ("herkeze" → "herkese"). Listedeki yanlışlar hiçbir kaynaktan öneri olarak da gelmiyor; yerine
   doğrusu öneriliyor ("birş" → "bir şey").
2. **Bitişik uzun soru eki.** "geliyormusun" → "geliyor musun" düzeltiliyor. Yalın "mi" bu kurala
   hiç girmiyor.

Bazı düzeltmeler iki kelimeye çıkıyor. HeliBoard bunları tek metin olarak yazıyor. Kullanıcı
geçmişine kelime kelime ekliyor. Geri almada da metnin tamamını siliyor. Bu kod okunarak
doğrulandı. Emülatör denemesi (`personal-e2e.sh`, "herşey" + boşluk → "her şey", geri silme →
"herşey") CI'a eklendi; bu ortamda çalıştırılamadı.

Fable'ın gözden geçirmesinden sonra yapılan değişiklikler aşağıda, "Gözden geçirme sonrası"
bölümünde. Tablodaki test sayıları resmî tek çalıştırmanın sayıları; düzeltme sonrası değerler
yanında ayrıca yazılı.

| Ölçü (kural-v0) | Hedef | dev | test |
|---|---|---|---|
| Tuzak vakalarda otomatik düzeltme | 0 | **0** (0/121 kelime, 32 vaka) | **0** (0/108 kelime, 32 vaka) |
| Kural setinde otomatik düzeltme kesinliği | ≥ %99 | **%100** (62/62) | **%98,4** (60/61) ✗ ölçülen; "orijinal" düzeltmesinden sonra %100 (59/59) |
| Yanlış yazım listesi duyarlılığı | ≥ %95 | **%100** (48/48) | **%100** (45/45); "hastahane" çıkarıldıktan sonra %97,8 (44/45) |
| Bitişik uzun soru eki duyarlılığı | ≥ %85 | **%93,3** (14/15) | **%100** (15/15) |
| Korpusta yanlış oto. düzeltme (iki kip) | ≤ %0,3; kurallardan en çok +0,01 puan | — | %0,10 / %0,11 · ham fark **+0,051 puan**, gözden geçirilmiş korpus yanlışları hariç **+0,000 puan** (bkz. "Korpus") |
| seed-v0 @0/@1/@3/KSR, aksan, yazım hatası ilk 3 | 0,2 puandan çok düşüş yok | düşüş yok | düşüş yok |
| Kişisel eşik; p95 ≤ 1,5 ms | geçmeli | geçiyor; seed-v0 p95 ~1,0–1,4 ms | |

İlk turda hedeflerden ikisi tam tutmadı; Fable ikisine de karar verdi (bkz. "Gözden geçirme sonrası").

- **Test kesinliği (%98,4).** Tek hata yeni kurallardan gelmiyor. Daha önce de olan ayrı yazılmış
  soru eki uyumundan geliyor (`DeasciifySource.questionParticle`): "orijinal mi" → "mı". Kuralların
  kendi yaptığı düzeltmelerin hepsi doğru: dev'de 62/62, testte 60/60. Bkz. "Test sonucu". Karar
  (a): "orijinal" ve benzeri alıntılar `front-harmony-tr.txt`'e eklendi; bu düzeltme testten
  **sonra** yapıldı, ölçülen değer %98,4 olarak kalıyor.
- **Korpus farkı (+0,051 puan).** Ham farkın tamamı 11 sözcükten geliyor. Bunlar korpusun
  kendisinde TDK'ya göre yanlış yazılmış sözcükler ("birşey", "sağol"); düzeltme doğru. Doğru
  yazılmış korpus sözcüklerinde yeni yanlış düzeltme yok (+0,000 puan). Karar (b): eşik, ayrı ve
  gözden geçirilmiş 6 sözcüklük bir listeyi (`data/korpus-yanlislari.tsv`) hariç tutan farka
  uygulanıyor; ham fark yanında yazılıyor.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Yanlış yazım listesi (CC0, 137 kayıt) | `engine/src/main/resources/io/github/kboard/engine/rules/yazim-yanlislari-tr.tsv` |
| Kural kaynağı | `engine/.../rules/SpellingRuleSource.kt` (`MisspellingList`, `SpellingRuleSource`) |
| Motor | `Api.kt`: `RuleCorrector` (`correction`, `replacement`), `Source.RULE` / `RULE_PARTICLE`, `Suggestions.autocorrectSource`. `SuggestionEngine`: kuralın düzeltmesi ilk yuvaya geçer ve otomatik düzeltme olur; başka kaynaktan gelen listedeki yanlış adayın yerine doğrusu konur |
| Yardımcılar | `DeasciifySource.hasFoldForm`, `isUserWord`; `FRONT_HARMONY_EXCEPTIONS` artık açık. `NgramSource.contains`, `containsFold` |
| CLI | `EvalCli`: kurallar `--deasciify` ile varsayılan olarak açık; `--no-rules`, `--no-rule-list`, `--no-rule-particle`, `--rules <tsv>`; yeni `revert` işlemi |
| Uygulama | `KBoardSuggestions`: kural kaynağı aksan düzelticiyle birlikte açılıyor; `noteRevert`, `replaceMisspellings` (HeliBoard'un kendi önerileri), `AutoCorrection.Replace.listRule`. `KBoardPersonal`: geri almalar `kboard/kural-geri-alinan.tsv`'de. `InputLogic.revertCommit` ve `DictionaryFacilitatorImpl.getSuggestions`: `// KBOARD:` satırları |
| Test seti | `eval/testset/kural-v0.jsonl` (196 vaka; yapay zekâ ile üretildi) |
| Ölçüm aracı | `testset.align` / `Case.aligned_targets`: tek yazılan kelime iki hedef kelimeye hizalanabiliyor. `simulate`: iki kelimelik hedef bağlamda iki kelime. `metrics`: `autocorrect_precision`, `fix_recall` |
| Eşikler | `.github/ci/kural-gate.py` (yeni). `data/tools/corpus_false_ac.py --compare yeni:taban=fark`, hariç listesi `data/korpus-yanlislari.tsv`. `ci.yml`: kural adımı, korpus adımında `kuralsiz` çalıştırması ve fark eşiği. `personal-e2e.sh`: iki kelimelik düzeltme ve geri alma |
| Dil modeli | `buildNgramModel` ve `corpus_false_ac.py`, kural-v0'ı da sızıntı süzgecine ekliyor |
| Testler | `engine/src/test/.../rules/SpellingRuleSourceTest.kt` (10 test; kök kaydında ünlü uyumu sınıfı denetimi dahil). `eval/tests`: hizalama ve iki kelimelik hedef. `app/src/test/.../KBoardSuggestionsTest.kt`: iki kelimelik düzeltme, kişisel sözlük, kullanıcı geçmişi, geri alma, HeliBoard önerilerinde yanlışın yerine doğrusu. `KBoardPersonalTest`: geri almanın kaydı, yüklenmesi ve "Öğrenilenleri sil" (Robolectric; CI'da çalışır, burada SDK yok) |

## Kurallar

### Yanlış yazım listesi

Satır biçimi: `yanlış<TAB>doğru[<TAB>kök]`. Kök kaydında yanlışın ve doğrunun son ünlüsü aynı uyum
sınıfında olmalı (kalın/ince, düz/yuvarlak) ve ikisi de ünlüyle ya da ünsüzle bitmeli; yoksa ek
uyumsuz kalırdı ("sezeryanla" → "sezaryenla"). Liste yüklenirken bu denetleniyor
(`MisspellingList.sameSuffixClass`), uymayan kök kaydı hata verir. Liste elle derlendi. Yayımlanmış bir liste
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

Yanlış yazım listesinde (karar, Fable 2. tur) yalnız iki istisna var:

- Kelime kişisel sözlükte (uygulamada kişiler de: HeliBoard'un `TYPE_CONTACTS` kaynağı).
- Kullanıcı aynı düzeltmeyi geri almış. `InputLogic.revertCommit` → `KBoardSuggestions.noteRevert`
  çağrılır. Geri alma kalıcı: `files/kboard/kural-geri-alinan.tsv` (cihaz korumalı depo, Android
  yedeği dışında, `yazılan<TAB>düzeltme`). "Öğrenilenleri sil" ve HeliBoard'un geçmiş silme yolu
  (`KBoardPersonal.clear`) dosyayı ve belleği temizler.

Kullanıcı geçmişi ve kişisel model listede istisna değil: yanlış yazım alışkanlıkla tekrarlanır,
tekrar onu doğru yapmaz. Uygulama bu yüzden liste düzeltmesini (`Source.RULE` →
`AutoCorrection.Replace.listRule`) aksan ve soru eki düzeltmesinden ayırıyor. Aksan ve soru ekinde
HeliBoard yazılanı kişisel sözlükte, kişilerde ya da kullanıcı geçmişinde bulduysa karar eskisi gibi
HeliBoard'da kalır (`USER_SOURCES`); listede yalnız kişisel sözlük ve kişilerde.

Bitişik soru ekinde kişisel modelde 2+ kullanım da istisna olarak kalıyor (yazılan "hiçbir yerde
olmamalı" koşulunun parçası).

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
olduğu için zararsız.

Emülatör denemesi `personal-e2e.sh`'e eklendi (2b adımı, tuş dokunuşlarıyla): "herşey" + boşluk →
alan "Her şey"; geri silme → "Herşey"; geri alma dosyası yazıldı mı; uygulama kapatılıp açılınca
"herşey" yine düzeltilmiyor mu; "Öğrenilenleri sil"den sonra dosya silindi mi. Bu ortamda emülatör
yok, **çalıştırılmadı**; geri silme tuşunun konumu (1015, 1973) ölçülmedi, harf sırasından
çıkarıldı.

### Listedeki yanlış öneri olarak da gelmez

Hiçbir kaynak listedeki yanlışı önermez; yerine doğrusu konur (yazılanın kendisi hariç):

- Motorda `SuggestionEngine`, kural dışı kaynakların adaylarını `RuleCorrector.replacement` ile
  değiştiriyor ("herş" → "her şeyi", "her şey"). Kişisel sözlük kaynağı (`Source.USER`) ve geri
  alınan yazımlar dokunulmaz.
- Uygulamada HeliBoard'un kendi sözlüklerinden (ana sözlük, kullanıcı geçmişi) gelen öneriler de
  `KBoardSuggestions.replaceMisspellings` ile değiştiriliyor; doğrusu zaten listedeyse yanlış
  çıkarılıyor. Değiştirilen öneri otomatik düzeltmeye uygun sayılmıyor (yalnız şeritte).

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

Fable'ın gözden geçirmesinden sonra (2. tur) iki değişiklik test sonucunu değiştirdi; ikisi de
testten değil, Fable'ın kararından geliyor ve test yarısına bakılarak ayarlanmadı:

- Karar (a), `front-harmony-tr.txt`'e "orijinal" ve 27 benzer alıntı: kural-liste-063 artık doğru.
- Karar (c), "hastahane" çıkarıldı (sözlükte ve Zemberek sözlüğünde var): kural-liste-082
  ("Hastahaneye" → "Hastaneye") artık düzeltilmiyor. Vakanın etiketi değiştirilmedi.

2. tur sonrası: dev tuzak 0/121, kesinlik 62/62, liste 48/48, soru eki 14/15; test tuzak 0/108,
kesinlik 59/59, liste 44/45, soru eki 15/15. Resmî test sonucu yukarıdaki ilk çalıştırmadır.

Testteki tek kesinlik hatası kural-liste-063: "Bu orjinal mi, çakma mı?". Bağlamdaki kelime
"orijinal" olduğu için önceki sürümden kalan ayrı soru eki uyumu "mi"yi "mı" yapıyor. "orijinal"
de "normal", "ideal" gibi ince ek alıyor ve `front-harmony-tr.txt`'e girmeli. İlk turda hata testte
bulunduğu için düzeltilmedi; 2. turda Fable'ın kararıyla düzeltildi (yukarıda).

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
sayıyor, ama yukarıdaki 11 sözcük doğru değil.

Karar (b, Fable): yazım kuralı listesinin tamamını hariç tutmak döngüsel olurdu (liste kendini
aklardı). Bunun yerine ayrı, gözden geçirilmiş bir liste var: `data/korpus-yanlislari.tsv`, 6
satır (birşey, herşey, sağol, sağolun, farkeder, sezeryan), her biri beklenen düzeltmesiyle. CI
eşiği (`--compare ...=0.0001`) yalnız tam bu sözcük + düzeltme çiftlerini hariç tutan farka
uygulanıyor. Ham fark her çalıştırmada yanında yazılıyor ve JSON'a da (`compare.delta`) yazılıyor.

2. tur sonrası ölçüm (aynı dilim):

| Fark | Ham | Gözden geçirilmiş korpus yanlışları hariç |
|---|---|---|
| varsayılan − kurallar kapalı | +0,051 puan (11 yeni, 0 giden) | **+0,000 puan** |
| varsayılan+bağlam − kurallar kapalı+bağlam | +0,051 puan (11 yeni, 0 giden) | **+0,000 puan** |

Yeni 11 düzeltmenin 11'i de bu listede; doğru yazılmış korpus sözcüklerinde yeni yanlış düzeltme
yok.

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

## Gözden geçirme sonrası (Fable, 2. tur)

- **Ünlü uyumunu değiştiren kök kayıtları (engel 1).** "mütevazi" → "mütevazı", "sezeryan" →
  "sezaryen", "vejeteryan" → "vejetaryen" kökte uyum sınıfını değiştiriyordu ("sezeryanla" →
  "sezaryenla" gibi olmayan kelimeler). Üçü de yalnız tam eşleşmeye çevrildi; liste yüklenirken
  denetim var (bkz. "Yanlış yazım listesi") ve testte varsayılan liste bu denetimden geçiyor.
- **Listedeki yanlış öneri olarak gelmiyor (engel 2).** Bkz. "Listedeki yanlış öneri olarak da
  gelmez".
- **İstisnalar (engel 3).** Listede yalnız kişisel sözlük (ve kişiler) ile geri alma; geri alma
  kalıcı, "Öğrenilenleri sil" temizler. Bkz. "Kullanıcı istisnaları". Kişiler (`TYPE_CONTACTS`) de
  istisna tutuldu: kişi adları kullanıcının açıkça verdiği adlardır; Fable isterse çıkarılır.
- **Karar (a).** `front-harmony-tr.txt`'e 28 alıntı eklendi, test setine bakılmadan derlendi:
  orijinal, terminal, sinyal, kristal, lokal, moral, liberal, rol, gol, alkol, kontrol, protokol,
  sembol, petrol, kabul, usul, mahsul, timsal, vokal, radikal, kardinal, spiral (sözlükte yalnız
  ince ekli çekimleri var), minimal, optimal, dijital, viral, total, mental (sözlükte çekim yok;
  ince l kuralıyla). Elenenler: kanal, formal, karnaval (sözlükte kalın ekli), santral (ikisi de),
  futbol (futbolu).
- **Karar (b).** Korpus eşiği ayrı, gözden geçirilmiş listeyle (bkz. "Korpus").
- **Karar (c).** değilmi, varmı, yokmu (tam), yinede, hemde, belkide ve sağol kaldı.
  "hastahane" çıkarıldı: `main_tr`'de (sıklık 88) ve Zemberek 0.17.1 sözlüğünde geçerli kelime.
  "konsentre" kaldı: `main_tr`'de, ek zinciri kök listesinde (`stems-tr.tsv`) ve Zemberek
  sözlüğünde (`lexicon.bin`) yok. Bilgi: Zemberek sözlüğünde "sezeryan" da var; Fable'ın kararıyla
  (b listesi) kaldı.

## Riskler ve doğrulanmayanlar

- **İki kelimelik düzeltme cihazda denenmedi.** Kod okundu, Robolectric testleri yazıldı, emülatör
  denemesi eklendi ama bu ortamda çalıştırılamadı. Uygulama bu ortamda derlenmedi (Android SDK
  yok); CI derleyecek.
- **Kullanıcı geçmişi listede istisna değil.** "herşey"i bilerek yazan kullanıcı her seferinde geri
  silmeli; bir kez geri alması kalıcı olarak yeter.
- **Geri alma dosyası küçük ama sınırsız.** Her geri alma bir satır; liste 137 kayıt ve çekimler
  olduğundan pratikte küçük kalır.
- **Sohbet dilinde rahatsızlık.** "sağol", "farkettim", "değilmi" bilerek bitişik yazılabiliyor;
  düzeltme TDK'ya göre doğru ama istenmeyebilir. Geri alma ve kişisel sözlük bunu kapatıyor.
- **Test seti kural yazarıyla aynı kaynaktan.** Yapay zekâ üretti, insan gözden geçirmedi;
  duyarlılık sayıları iyimser.
- **`data/suffix/chains-tr.tsv` yeniden üretilmedi.** Zemberek gerekiyor; kural-v0 bu yüzden ek
  zinciri tablosunun sızıntı süzgecinde yok. Tablo yalnız soyut zincir sayıları içeriyor.
- **CI eşiği kural-v0'ın tamamında** (dev + test) uygulanıyor; hedef tablosu test yarısı içindir.
