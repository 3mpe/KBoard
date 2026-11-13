# Faz 3: kişisel sözlük için ek üretici

Tarih: 2026-11-12 · Dal: `morph` · Plan: EVALUATION.md §1, öneri madde 2 ("cihazda hafif ek üretici,
`engine/morph`").

Hedef: kullanıcı "Kadıköy" eklediğinde `Kadıköy'd` ya da `Kadikoy'd` yazılırken "Kadıköy'de"
önerilsin.

## Ne yapıldı

| Parça | Dosya |
|---|---|
| Kural tabanlı ek üretici (saf Kotlin, bağımlılık yok) | `engine/src/main/kotlin/io/github/kboard/engine/morph/SuffixGenerator.kt` |
| Aday kaynağı (`CandidateSource`, `UserWordsAware`) | `engine/.../morph/UserWordSuffixSource.kt` |
| CLI: varsayılan açık, `--no-user-morph` ile kapanır | `engine/.../cli/EvalCli.kt` |
| Testler (84 satırlık tablo + kaynak testleri) | `engine/src/test/.../morph/` |

### Üretilen ekler

Sıra, öneri sıralamasındaki önceliktir: `-DA`, `-(y)A`, `-DAn`, `-(n)In`, `-(y)I`, `-(y)lA`, `-lAr`,
`-DAki`, `-lArI`, `-lArDA`, `-lArDAn`. Kök başına 11 biçim; kişisel sözlük değişince bir kez üretilir.

### Kurallar

- **Ünlü uyumu:** kökün son ünlüsünden; iki yönlü (a/e) ve dört yönlü (ı/i/u/ü). İki adımlı dizide
  ikinci ek birinci ekin ünlüsüne bakar (`kitaplarda`, `gözleri`).
- **İnce ek alan alıntılar:** küçük bir liste (saat, kalp, hal, rol, alkol, gol, kontrol, petrol,
  sembol, futbol, kabul, hayal, ihtimal, harf, Kemal...). Yuvarlaklık korunur: `rolü`, `saati`, `halde`.
- **Benzeşme:** f s t k ç ş h p'den sonra D → t (`Işık'ta`, `kitapta`, `Beşiktaş'tan`).
- **Kaynaştırma:** ünlüyle biten köke `(y)A`, `(y)I`, `(y)lA`'da y (`Ankara'ya`, `Çağla'yla`);
  `(n)In`'de n (`Tuana'nın`, `Kartopu'nun`).
- **Yumuşama:** yalnızca cins isimde, ünlüyle başlayan ekten önce: p→b, ç→c, k→ğ, nk→ng, g→ğ
  (`kitabı`, `ağaca`, `köpeği`, `rengi`, `psikoloğa`).
  - Tek heceliler yumuşamaz (`topu`, `eti`, `saçı`, `parkı`); istisna listesi: kap, dip, cep, grup,
    kalp, uç, taç, güç, genç, borç, renk, denk, dert, kurt, yurt, tat, dört, gök, çok.
  - t→d yalnız listede (kanat, umut, yoğurt, kilit, armut, kâğıt...); çok heceli -t'lerin çoğu
    Arapça -at/-et alıntısıdır ve yumuşamaz (`devleti`, `saati`).
  - Yumuşamayan çok heceli alıntılar: hukuk, ahlak, idrak, ittifak, tebrik, evrak...
- **Özel isim:** ilk harf büyükse ekten önce kesme işareti; yazım korunur (`Mehmet'e`, `Zeynep'i`,
  `Ayvalık'taki`).
- **Ünlüsüz kısaltma:** büyük harfli ve ünlüsüzse son harfin okunuşu (Y "ye", T "te") kullanılır:
  `THY'ye`, `PTT'nin`, `PTT'de`. Ünlüsü olan kısaltma kelime gibi okunur (`NATO'ya`).
- **Büyük/küçük harf:** `TurkishText.lower` (I→ı, İ→i): `Ilgın'ın`, `İzmir'de`, `İlkim'e`.

### Kaynak ve puanlama

- Çekimler katlanmış ve kesme işaretsiz anahtara göre sıralı dizide durur (`Kadıköy'de` →
  `kadikoyde`). Sorgu: ikili arama + önek aralığı. `Kadikoy'd`, `kadıköy'd`, `Kadikoyd` hepsi eşleşir.
  Yazılanda aksanlı harf varsa çekim aynı konumda aynı harfi taşımalıdır.
- Kök yazılıyorken (`Kadık`, en az 3 harf): kök DeasciifySource'tan 1.0 ile gelir, kök başına
  yalnız en sık çekim 0.80 ile eklenir. Sözlük tamamlamaları (en çok 0.85) öne geçer; tek bir
  kişisel kelime ("Selin") `se` yazarken öneri şeridini doldurmaz.
- Yazılan kelime sözlükte ya da kişisel sözlükte zaten geçerliyse ("kısa", "kasa", "ise") çekim
  ("kış" → "kışa") önerilir ama otomatik düzeltme yapılmaz.
- Kişisel sözlük süreç başında da motora verilir; telefon yeniden başlatılınca çekimler kaybolmaz.
- Kök geçildiyse (`Kadıköy'`, `Kadıköy'd`): 0.97'den ek sırasıyla azalan.
- Tam eşleşme (`Kadikoy'de`): 1.0; oto. düzeltilebilir yalnızca yazılan farklıysa **ve** ya kesme
  işareti yazılmış ya da çekim kesmesizse (cins isim). `modada` → `Moda'da` düzeltilmez (gerçek
  kelimeyle çakışabilir).

## Ölçüm

Komut: `kboard_eval simulate testset/seed-v0.jsonl --predictor subprocess --command "engine --deasciify
data/out/deasciify-tr.kbd"` (327 vaka, 1250 kelime). Önce = aynı komut, ek üretici yok.

| Kırılım | İlk-3 @1 | İlk-3 @2 | İlk-3 @3 | KSR | Aksan düzeltme | Yanlış oto. düzeltme |
|---|---|---|---|---|---|---|
| **Genel**, önce | %8.2 | %11.8 | %24.7 | %17.1 | %78.2 | %0.1 |
| **Genel**, sonra | %9.5 | %13.3 | %26.4 | %19.3 | %78.2 | %0.1 |
| **Genel**, inceleme sonrası | %8.2 | %11.8 | %25.9 | %18.7 | %78.2 | %0.1 |
| kullanici_sozlugu, önce | %11.7 | %15.1 | %21.0 | %14.4 | %92.3 | %0.0 |
| kullanici_sozlugu, sonra | %20.3 | %24.0 | **%30.4** | **%26.9** | %92.3 | %0.0 |
| kullanici_sozlugu, inceleme sonrası | %11.7 | %15.1 | **%27.6** | **%23.4** | %92.3 | %0.0 |
| etiket: kesme, önce | %5.0 | %9.1 | %13.9 | %10.3 | %91.7 | %0.0 |
| etiket: kesme, sonra | %14.9 | %19.3 | **%24.2** | **%23.8** | %91.7 | %0.0 |
| etiket: ek, önce | %5.6 | %9.7 | %13.4 | %11.2 | %87.5 | %0.0 |
| etiket: ek, sonra | %15.2 | %19.4 | %23.2 | %24.1 | %87.5 | %0.0 |

"İnceleme sonrası" satırları kod incelemesindeki üç düzeltmeden sonra: kök öneki yazılırken çekim
sayısı sınırlandığı için @1/@2 düştü, @3 eşiğin (%25) üstünde kaldı. Gerçek kullanımda sözlük
tamamlamalarını ezmemek bu kayıptan daha önemli.

Diğer kategoriler (dogru_yazim, sohbet, ozel_isim) değişmedi: kaynak kişisel sözlük boşken aday
döndürmez. Gecikme p95 0,36 → 0,34 ms (gürültü içinde).

Kullanıcı köküyle başlayan 58 hedef kelimenin 42'si ekli; bunların 18'i ilk harfte (@1) ilk 3'te
(ekli hâliyle). Gerisinin çoğu
kesmeden sonraki ilk harfte yakalanıyor (`Selin'l` → `Selin'le`), çünkü kök yazılırken yalnızca en
sık iki ek (`-DA`, `-(y)A`) sığıyor.

## Sınırlamalar

- **Bağlam yok:** kök yazılırken hangi ekin geleceği bilinmiyor; sıra sabit (`-DA` önce). Kişi
  adlarında `-(n)In`/`-(y)lA`, yer adlarında `-DA`/`-DAn` daha olası; ileride n-gram veya
  kullanıcı geçmişiyle sıralanabilir.
- **Kesmeden sonra ilk yuva:** DeasciifySource kesmeli yazımda yarım eki de 1.0 ile önerir
  (`Kadıköy'd`); çekimler 2. ve 3. yuvaya düşer. `-DAki` gibi uzun ekler ancak `-DA` yazılınca
  ilk 3'e giriyor (`Akaretler'deki`, `Ayvalık'taki`).
- **Tamlama ekli kökler:** `Nişantaşı'na`, `Parkı'nda` (3. tekil iyelikten sonra n) üretilmiyor.
- **Ünlü düşmesi** (`ağız → ağzı`, `burun → burnu`, `oğul → oğlu`) ve `su → suyu`, `ne → neyi`
  gibi düzensizlikler yok.
- **Yumuşama ve alıntı listeleri küçük ve elle**; listede olmayan sözcükte kural tahmin eder
  (ör. çok heceli -t: yumuşamaz varsayılır, `bulut→bulutu` doğru, `geçit` listede).
- Rakamla biten kökler (`2024'te`) ve ünlüsüz küçük harfli kökler (`km`) atlanır.
- Yalnız 11 ek dizisi; iyelik ekleri, fiil çekimleri ve `-ki`'den sonra gelen ekler yok.
