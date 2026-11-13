# Bağlama göre aksan seçimi (plan adım 4)

Aksan düzeltici (`DeasciifySource`) artık boşlukta karar verirken önceki kelimelere bakar.
Üç yeni kural, eski sözlük kuralının üstüne eklenir:

1. **Soru eki uyumu.** Ayrı yazılan `mi, misin, miyim, miyiz, misiniz, midir, miydi(m/n/k/niz),
   miymiş` (ve u'lu biçimleri), önceki kelimenin son ünlüsüne uydurulur: "yemeğe çıkalım mi" → mı,
   "pencereyi kapatır misin" → mısın. Yazılan i yalnız i/ı, u yalnız u/ü olabilir. Önceki kelime
   ASCII yazılmışsa önce onun düzeltmesine bakılır ("Saat kacta mi" → kaçta → mı). Dil modeli
   gerektirmez.
2. **Sözlük kararı bağlamla değişir.** Sözlük kuralı bir biçim seçtiyse ama dil modeli bağlamda başka
   bir biçimi en az 1,5 (log10) daha olası buluyorsa o biçim seçilir.
3. **Bilinmeyen ASCII kelime.** Yazılan ne aksan sözlüğünde ne dil modelinde varsa, aynı katlanmış
   biçimdeki model kelimelerinden bağlamda en olası olan, ikinciden en az 1,0 öndeyse seçilir
   ("Saat kacta" → kaçta, "Kapıyı caldim" → çaldım).

Hiç dokunulmayanlar: tek harf, aksanlı yazılmış kelime, koruma listesi, kişisel sözlük ve aksan
sözlüğünde geçerli olan kelimeler ("Kir", "lazim"). Son grup sıklıkla ayırt edilemiyor: kir/kır farkı
26, lazim/lazım farkı 29. Bu yüzden "lazim" yalnız öneri olarak kalır (ilk öneri lazım). Geçerli
kelimeyi model farkıyla düzeltme (`lmValidMargin`) denendi; dev'de etkisi yok, kapalı.

Ayarlar yalnız dev yarısında seçildi. Değişim eşiği 1,0 dev'de bir kelime daha kazandırıyor; fark
gürültü düzeyinde, temkinli 1,5 bırakıldı.

## Sonuç

Eski = ana dal davranışı (`--no-context-accent --no-question-harmony`); yeni = varsayılan. Aynı
dil modeli ve aynı motor derlemesi.

| | dev eski | dev yeni | **test eski** | **test yeni** | tüm set eski | tüm set yeni |
|---|---|---|---|---|---|---|
| Aksan düzeltme | %77,4 (133) | %91,7 | **%78,8 (156)** | **%89,1** | %78,2 (289) | %90,3 |
| — belirsiz etiketi | %53,6 (28) | %75,0 | %69,7 (33) | %75,8 | %62,3 (61) | %75,4 |
| Yanlış oto. düzeltme | %0,2 (434) | %0,2 | %0,0 (470) | %0,0 | %0,1 (904) | %0,1 |
| İlk-3 @0 / @1 / @3 | 21,2 / 31,4 / 53,0 | aynı | 20,2 / 29,8 / 51,6 | aynı | 20,7 / 30,6 / 52,3 | aynı |
| KSR | %40,3 | %40,3 | %39,5 | %39,5 | %39,9 | %39,9 |
| Gecikme p95 | 1,11 ms | | 1,03 ms | | 1,06 ms | 1,14–1,23 ms |

İlk-3 @2 testte 38,5 → 38,3 (ara öneklerde düzeltme adayı bir yer kaplıyor, ör. "oc" → öç); diğerleri
aynı. Tek harfte ("s" → ş) dil modeli kuralı çalışmaz; bu, ilk denemedeki @1 düşüşünü gideriyordu.

Tüm sette (soru eki uyumu açık eskiye göre) 58 aksan hatası 28'e indi; yeni hata yok (kelime kelime
karşılaştırıldı). Test yarısında
hedef "genel ≥ %86" tuttu; "belirsiz ≥ %78" tutmadı (%75,8). Kalan belirsizlerin çoğu cümle başında,
bağlamsız iki geçerli kelime: Su/Şu, Cam/Çam, On/Ön, Kus/Kuş, Gol/Göl. Bunlar ancak sağ bağlamla
(sonraki kelimeler yazıldıktan sonra geri dönüp düzeltme) çözülür; ayrı bir adım.

Korpusta yanlış otomatik düzeltme (her 10. satır ayrılır, dil modeli onsuz derlenir; 21.687 sözcük):

| | bağlamsız soru | bağlamlı soru (yeni `--context`) |
|---|---|---|
| eski | %0,04 | %0,05 |
| yeni | %0,05 | %0,06 |

Bağlamlıda yeni çıkan iki örnekten biri korpusun kendi hatası ("adam miydin" → mıydın doğru), biri
var olan "Sahi → Şahı" hatasının devamı ("Sahi mi" → mı). CI artık iki ölçümü de %0,3 eşiğiyle
denetler; aksan düzeltme eşiği %75'ten %88'e çıktı.

## Uygulama

`KBoardSuggestions.rebuild()` dil modelini aksan düzelticiye bağlar (`contextScorer`).
`mergeAutoCorrection` artık `NgramContext` alır; `Suggest.kt` aynı bağlamı geçirir. Böylece telefonda
boşluğa basınca verilen karar, ölçümdeki kararla aynıdır. Hata ayıklama için eklenmiş `Log.d`
satırları kaldırıldı.
