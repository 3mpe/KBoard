# Yazım kuralları, PR B: bitişik yazılmış de/da/ki için ayrık öneri (yalnız öneri)

**Özet.** "Bende geldim", "dedimki", "Hocamda biliyor" gibi bitişik yazılmış bağlaçlar için öneri
çubuğunda ayrık biçim ("Ben de", "dedim ki", "Hocam da") çıkıyor. Bu yalnız bir öneri. Boşluğa
basınca kelime değişmiyor. Doğru yazılmış bulunma eki ("bu akşam bizde", "evde", "okulda") çoğunlukla
dokunulmadan kalıyor. Sağ bağlam (sonraki kelime) henüz kullanılmıyor, o yüzden cümle başındaki "Bende"
gibi iki okunuşu da olan kelimelerde karar yalnız sol bağlam ve dil modeliyle veriliyor.

Tasarım Fable'ın kararı (2026-11-13). Test seti ayardan önce donduruldu, eşikler yalnız dev yarısında
ayarlandı.

## Sonuçlar

Test yarısı (`dedaki-v0`, kelimenin tamamı yazıldığında, boşluktan önceki ilk 3 öneri). Köşeli
parantezde vakalar üzerinden 2 000 örnekli bootstrap ile %95 aralık.

| Ölçü | Hedef | Test |
|---|---|---|
| Bitişik bağlaç: ayrım ilk 3'te | ≥ %60 | **%75,9** [%63,0–%87,0] (54 kelime) |
| Bitişik bağlaç: ayrım ilk yuvada | ≥ %40 | **%66,7** [%53,7–%79,6] |
| · yalnız de/da (40) | | ilk yuva %55,0, ilk 3 %67,5 |
| · yalnız ki (14) | | ilk yuva %100, ilk 3 %100 |
| Tuzak vakada ayrım ilk yuvada | ≤ %10 | **%8,6** [%0–%20] (35 vaka; 3 vaka) |
| Tuzak vakada ayrım ilk 3'te | eşiksiz (Fable ~%40 üstüne itiraz) | %22,9 |
| Ayrım otomatik düzeltme oldu | 0 | **0** |
| Korpus: doğru yazılmış -de/-da/-te/-ta/-ki'de ayrım ilk yuvada | ≤ %5 | **%0,59** (6/1015) |
| Korpus: aynısı ilk 3'te | rapor | %1,58 (16/1015) |
| seed-v0 @0/@1/@3/KSR, aksan, yazım hatası | düşüş ≤ 0,2 puan | değişmedi (aynı) |
| kural-v0 @0/@1/@3 | düşüş ≤ 0,2 puan | değişmedi; KSR +0,17, yazım hatası ilk 3 +6,3 puan |
| kural-v0 eşikleri (PR A) | geçmeli | geçti |
| Hız p95 (seed-v0, bilgisayarda) | ≤ 1,5 ms | 1,12 → 1,23 ms |

Sayılar yerelde, main'in dil modeliyle ölçüldü. Bu PR yeni seti dil modelinin sızıntı süzgecine de
ekliyor; CI'daki resmî sayılar o modelle çıkar (bkz. "CI").

**Testin iki kez okunması.** Test yarısı ilk kez okunduğunda de/da ilk yuvası %47,5 idi. Kaçanlar
arasında "Söyledimde", "Aradımda" vardı: Fable'ın tasarımındaki "çekimli fiil + bitişik biçim hiçbir
yerde yok → kesin sınıf" kuralı ilk sürümde eksikti. Kural tasarıma göre eklendi, dev yarısında
denendi (dev ilk yuva %62,3 → %64,2, tuzak değişmedi), sonra test bir kez daha ölçüldü. Tabloda bu
ikinci ölçüm var. İlk ölçüm: hata ilk yuva %61,1, ilk 3 %70,4; tuzak aynı.

## Kural

`engine/.../rules/ConjunctionSplitter.kt`. Yazılan kelime W, sol parça L.

**de/da** (W -de/-da/-te/-ta ile biter; L sözlükte ya da dil modelinde, gerekirse aksanlı okunuşu:
"agacta" → "ağaç da"; bağlacın ünlüsü L'ye uyar, front-harmony-tr.txt istisnalarıyla):

1. **Kesin sınıf → ilk yuva.** L kapalı listede (ya, hem, yine, gene, ne, çok, daha, hiç, pek, ama,
   bile, zaten, artık, belki, sonra, bana/sana/ona..., bence, kimse, herkes, hepsi...), ya da W'nin
   ünsüzü bulunma ekine uymuyor ("mehmetde", "yemekde", "maçda": bulunma eki -te/-ta olurdu), ya da L
   çekimli bir fiil ve W hiçbir yerde yok ("söyledimde"). Kişisel model bu sınıfı bastırmaz (Fable:
   "yada" yazmaya alışmış kullanıcı da görmeli); yalnız kişisel sözlük bastırır.
2. **-te/-ta ünlüden ya da yumuşak ünsüzden sonra** ("kibrite" = kibrit + e) hiç ayrılmaz.
3. **Belirsiz sınıf** (W, L'nin geçerli bulunma eki biçimi: "bende", "evde"). Dil modeli karar verir:
   r = log P(L | bağlam) + log P(de | bağlam, L) − log P(W | bağlam). r ≥ 2,0 ilk yuva, r ≥ 0,5 üçüncü
   yuva, altında öneri yok. W dil modelinde yoksa ("kardeşimde", "yalnızlıkta") karar
   log P(de | bağlam, L) ile: ≥ −1,5 ilk yuva, ≥ −2,5 üçüncü yuva. W kişisel modelde 2+ kullanılmışsa
   en çok üçüncü yuva. Tek harfli L ("oda" → "o da") üçüncü yuvaya hiç girmez: o yuva "oda" ile
   başlayan tamamlamanın (odada) yeri (kural-v0 @3'te 0,37 puanlık düşüşü bu kapattı).

**ki** yalnız L çekimli bir fiilse ("dedim", "biliyorum", "baktım") ya da kapalı listedeyse (öyle,
şöyle, böyle, demek, tabii, yeter, kaldı, neyse, yazık, bil, eminim, umarım, sanırım...) ve W bilinen
bir kelime değilse. Bitişik yazılan ki'li kelimeler adıyla dışarıda (belki, çünkü, sanki, oysaki,
mademki, halbuki/hâlbuki, meğerki, illaki). Zamir, ilgi eki, zaman kelimesi ve -de/-da sonrasındaki
ki (benimki, seninki, yarınki, evdeki) hiç ayrılmaz.

**Motor.** `SplitSuggester` arayüzü; `SuggestionEngine` ayrık biçimi ilk yuvaya (başta otomatik
düzeltme adayı varsa onun hemen arkasına) ya da üçüncü yuvaya koyar. Puanlar artmayan kalır (uygulama
puana göre sıralar). Yeni kaynak türü `Source.RULE_SPLIT`, hiçbir zaman otomatik düzeltmeye uygun
değil. Yanlış yazım listesinin (PR A) düzeltmesi varsa o önce gelir ("yinede" → "yine de" boşlukta
düzelir, bu PR'dan önce de öyleydi).

**Uygulama.** `KBoardSuggestions`: ayırıcı yazım kurallarıyla birlikte, aksan düzelticiyle açılır
(ayrı bir ayar yok; yazım kuralları gibi). HeliBoard ilk öneriyi puanına bakıp kendisi de otomatik
düzeltme yapabildiği için, ilk öneri ayrık bağlaçsa (KBoard kaynağı, boşluklu, düzeltmeye uygun
değil) `mergeAutoCorrection` düzeltmeyi kapatır. Seçilen öneri boşluklu tek metin olarak yazılır;
HeliBoard kullanıcı geçmişine (ve kişisel öğrenmeye) kelime kelime ekler, bitişik biçim öğrenilmez.

## Bilinen eksikler

- **Sağ bağlam yok.** Cümle başındaki "Bende/Sende/Bizde" hem hata hem tuzak setinde çok. Doğru karar
  çoğu zaman sonraki kelimeye bağlı ("Bende kalsın" / "Ben de geldim"). Bu, yol haritasındaki "sağ
  bağlamla geri düzeltme" adımına kalıyor. Tuzaklarda ilk yuvaya çıkan üç vakanın üçü de bu türden
  ("Bende de aynı sorun", "Annemde biraz para", "annemde kalacağım").
- **"herkesde" PR A listesinde** "herkeste"ye düzeltiliyor; "Herkesde biliyor" için doğrusu "Herkes
  de". Liste kaydı bulunma eki okunuşunu seçiyor. Fable'ın kararına bırakıldı: kayıt kaldırılırsa
  ayırıcı ilk yuvada "herkes de" önerir (herkes kapalı listede).
- **Kesmeli özel isim** ("Ankara'da" → "Ankara da") bu sürümde yok: kesmeli kelimeyi motor öneri
  kaynaklarına hiç sormuyor.
- **Dil modelinde olmayan sol parça** ("gitsen", "istersen") ayrılmıyor.
- **Ölçüm yapay veriyle.** Test seti yapay zekâyla yazıldı ve hata/tuzak dengesi elle seçildi. Korpus
  denetimi gerçek metinde doğru yazılmış kelimeleri sayıyor; korpusun kendi bitişik yazım yanlışları
  ("Bende" 2 kez) da orada ayrım sayılıyor.

## CI

- `de/da/ki ayrımı` adımı: `dedaki-v0` test yarısı, `.github/ci/dedaki-gate.py` (ilk 3 ≥ %60, ilk
  yuva ≥ %40, tuzak ilk yuva ≤ %10, ayrım otomatik düzeltme 0).
- `Korpusta ayrık bağlaç önerisi` adımı: `data/tools/corpus_split.py` (ilk yuva ≤ %5).
- Emülatör: `.github/ci/dedaki-e2e.sh` ("dedimki" → şeritte "dedim ki", dokununca iki kelime;
  hemen geri silme yazılanı geri getirir; boşluk düzeltmez; "bu akşam bizde" ilk öneri ayrım değil).
- `dedaki-v0` dil modeli derlemesinin ve korpus aracının sızıntı süzgecine eklendi.

## Dosyalar

| Alan | Dosya |
|---|---|
| Motor | `Api.kt` (`SplitSuggester`, `Source.RULE_SPLIT`), `SuggestionEngine.kt` (`placeSplit`), `rules/ConjunctionSplitter.kt`, `cli/EvalCli.kt` (`--no-split`, `--split-params`) |
| Uygulama | `kboard/KBoardSuggestions.kt` |
| Testler | `engine/src/test/.../rules/ConjunctionSplitterTest.kt` (10), `app/src/test/.../KBoardSuggestionsTest.kt` (adaptör: öneri, düzeltme yok, puan sırası, kişisel sözlük) |
| Ölçüm | `eval/testset/dedaki-v0.jsonl`, `.github/ci/dedaki-gate.py`, `data/tools/corpus_split.py`, `.github/ci/dedaki-e2e.sh` |
