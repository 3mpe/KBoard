# Faz 7: kişisel öğrenme (sık kullanılanlar öneride öne çıkar)

Tarih: 2026-11-13. İstek: "sık sık kullandıklarımı gruplayıp kelime önerisi sırasında göz önüne alıp
öncelik verebilir". Tasarım ve kabul hedefleri Fable'ın kararıdır. Ölçüm masaüstü motorla
(`engine` CLI, subprocess öngörücü); uygulama tarafı yalnız CI'da derlenip sınanır (aşağıda
"Doğrulanmayanlar").

## Tasarım

- **Motor, `personal/PersonalModel`**: tek kelime + önceki kelime → kelime (cümle başı `<s>` dahil)
  sayıları. Anahtar Türkçe küçük harf (`TurkishText.lower`), gösterim biçimi ayrı tutulur (yalnız
  cümle başında görülmüş büyük harfli biçim ortada dil modelinin biçimiyle gösterilir: "Yarın" →
  "yarın"). Trigram yok (tekrar eden @0 hedefi onsuz tuttu).
- **Sönüm**: yazma hacmiyle, yarı ömür 5 000 kelime, her kayıtta son görülme tikinden tembelce.
  Üstel sönüm bütün kayıtlarda aynı hızda olduğundan satır toplamları ve genel toplam artımlı tutulur.
- **Sınırlar**: 5 000 kelime, 20 000 ikili; aşılınca sönmüş sayısı en düşük %10 atılır (atılan
  kelimeye giden/gelen ikililer de). Dolu depo dosyası 263 KB (sınır 400 KB).
- **Karışım, `personal/PersonalSource`**: `NgramSource`'u sarar ve motorda onun yerini alır; bir kelime
  tek kaynaktan, tek olasılıkla gelir (çift sayım yok).

      P(w | v) = (1 − λ) · P_lm(w | bağlam) + λ · P_user(w | v)
      P_user(w | v) = (c(v, w) + κ · c(w) / N) / (c(v) + κ),   κ = 1
      λ = max(min(0,7, c(v) / (c(v) + 5)), λ₀),   λ₀ = 0,1 (tamamlama), 0 (sonraki kelime)

  Skor `NgramSource` ile aynı eşleme (`0,95 + eğim · log10 P`, en çok 0,98). Adaylar: dil modelinin
  adayları (karışık olasılıkla yeniden puanlanır) + kişisel modelden bağlam ardılları / önekle
  eşleşen kelimeler (dil modelinde olmayanlar dahil). Hiçbir kişisel aday otomatik düzeltme yapmaz.
  **Model boşken çıktı bugünküyle bayt bayt aynıdır** (seed-v0 izi 10 027 olayda fark yok).
- **Kurallar**: sözlükte ve dil modelinde olmayan kelime en az 2 kullanımda önerilir (tek seferlik
  yazım hatası öğrenilmez) ve o zaman yazım hatası düzeltmesi (`TypoSource`) onu bilinen kelime
  sayar. Rakamlı, 32 karakterden uzun, harf/kesme/tire dışı karakterli kelimeler öğrenilmez.
  Yasaklı kelimeler sonraki kelime olarak hiç, tamamlama olarak en az 3 kullanımda önerilir; bu
  adaylar `Source.PERSONAL` taşır ve motor bunları yasak listesinden geçirmez, kuralı kaynak uygular.
- **Geri alma**: son 16 öğrenmeden eşleşen en yenisi geri çıkarılır. Uygulamada HeliBoard'un
  `unlearnFromUserHistory` yolu (kelime son 25 sn'de yazıldıysa: silme, düzeltmeyi geri alma,
  öneriyi reddetme) çağırır.
- **Uygulama** (`kboard/KBoardPersonal`): besleme `DictionaryFacilitatorImpl.addWordToUserHistory`'den
  (yalnız Türkçe); HeliBoard'un gizli mod, parola/önerisiz alan, yavaş bağlantı, kişiselleştirme
  ayarı ve "saldırgan kelimeleri engelle" kuralları aynen geçerli. Dosya `files/kboard/personal-tr.bin`
  (cihaz korumalı depo, HeliBoard'un kullanıcı geçmişiyle aynı yer), geçici dosya + yeniden
  adlandırmayla atomik, son değişiklikten 10 sn sonra, arka plan iş parçacığında. Android yedeği ve
  cihaz aktarımı `kboard/` dizinini dışlar; HeliBoard'un elle yedeği yalnız izinli dosyaları alır.
  İçerik hiçbir günlüğe yazılmaz. Silme: HeliBoard'un geçmiş silme yolu (kişiselleştirme kapatılınca
  da çağrılır) ve Ayarlar > Metin düzeltme > **Öğrenilenleri sil**. Kişiselleştirme kapalıyken
  kişisel kaynak motordan çıkarılır. Dışa aktarma yok.

## Test seti: `eval/testset/kisisel-v0.jsonl`

8 sentetik kullanıcı × 40 mesaj (320 vaka, 1 715 kelime), yapay zekâ ile üretildi (Claude), seed-v0
okunmadan yazıldı, CC0-1.0. Her kullanıcının tekrar eden adları (Ecoş, Elifçik, Handoş, Tolgiş...),
yerleri (Bornova, Kızılay, Kadıköy, Maltepe...) ve kalıpları ("toplantıdayım, sonra ararım", "akşam
eve geçerken ekmek alırım", "teslimat saat kaçta") farklı sözcüklerle tekrar eder. `user` alanı bir
kullanıcının akışını belirtir; **dev/test ayrımı kullanıcıya göre**: dev = deniz-ogrenci,
zeynep-hemsire, emre-yazilimci, ali-uzak; test = mehmet-muhendis, ayse-emekli, kerem-halisaha,
selin-pastane. `new_words`: sözlükte ve dil modeli korpusunda olmayan kelimeler (18 kök).
Dil modeli derlemesi bu seti de sızıntı süzgecine verir (`buildNgramModel`, `corpus_false_ac.py`).

Ölçüm kipi `simulate --online`: her mesaj puanlandıktan sonra hedef kelimeleri sırayla öğretilir;
model başta ve kullanıcı değişince sıfırlanır. Kırılımlar:

- **tekrar**: (önceki kelime → kelime) ikilisi kullanıcının *önceki* mesajlarında geçti (öğrenme
  mesaj bitince yapıldığından aynı mesaj içindeki tekrar sayılmaz; ilk kelimenin öncesi `<s>`);
- **ilk kez**: geri kalanlar (kelimenin kendisi daha önce başka bağlamda geçmiş olabilir);
- **iki kullanımdan sonra yeni kelime**: `new_words`'teki kelime önceki mesajlarda en az 2 kez
  geçtiyse, en geç 2. harfte ilk 3'te mi.

Karşılaştırma tabanı **aynı motor, öğrenme kapalı** (`--personal`, `--online` yok).

## Ayar (yalnız dev kullanıcıları)

λmax = 0,7 ve K = 5 Fable'ın kararı, değiştirilmedi. Denenen (dev, ilk kez @0 / @3 / tekrar @0):

| λ₀ tamamlama | λ₀ sonraki kelime | κ | ilk kez @0 | ilk kez @3 | tekrar @0 |
|---|---|---|---|---|---|
| 0,2 | 0,2 | 1 | %14,0 | %56,1 | %91,7 |
| 0,2 | 0,05 | 1 | %14,2 | %56,1 | %91,7 |
| 0,2 | 0 | 1 | %14,9 | %56,1 | %91,7 |
| **0,1** | **0** | **1** | **%14,9** | **%56,4** | **%91,7** |
| 0,05 | 0 | 1 | %14,9 | %56,4 | %91,7 |
| 0,2 | 0 | 2 | %14,9 | %56,1 | %90,7 |
| 0,2 | 0 | 0,5 | %14,9 | %56,3 | %88,9 |

(dev taban ilk kez @0 %15,2.) Sonraki kelimede λ₀ > 0, bağlamda görülmemiş kişisel kelimeleri genel
kelimelerin ("bu", "bir") önüne geçiriyordu.

## Sonuç

Bütün sayılar %; parantezde 200 örnekli vaka bazlı bootstrap %95 güven aralığı. Vakalar kullanıcı
akışına bağlı olduğundan (bağımsız değil) aralıklar iyimserdir.

### Test kullanıcıları (rapor)

| Kırılım (n @0) | Metrik | Öğrenme kapalı | Öğrenme açık | Fark | Hedef | |
|---|---|---|---|---|---|---|
| tekrar (134) | İlk-3 @0 | 35,1 (28,5–41,4) | **91,0** (86,2–95,8) | **+55,9** | ≥ +20 ve ≥ 45 | ✓ |
| tekrar | İlk-3 @1 | 30,5 (24,9–36,2) | **93,6** (90,4–96,9) | +63,1 | ≥ 60 | ✓ |
| tekrar | KSR | 34,7 | 71,8 (68,3–75,3) | +37,1 | | |
| tüm set (695) | KSR | 35,1 (33,4–37,0) | **47,3** (44,8–49,6) | **+12,2** | ≥ +8 | ✓ |
| tüm set | İlk-3 @0 / @1 / @3 | 19,4 / 31,0 / 50,5 | 29,5 / 51,9 / 64,7 | | | |
| ilk kez (561) | İlk-3 @0 | 15,7 (12,8–18,2) | **14,8** (11,9–17,4) | **−0,9** | ≥ −0,5 | ✗ |
| ilk kez | İlk-3 @3 | 49,4 (45,1–53,0) | 52,3 (48,0–55,7) | +2,9 | ≥ −0,5 | ✓ |
| ilk kez | KSR | 35,3 (33,3–37,1) | 37,4 (35,2–39,3) | +2,1 | ≥ −0,5 | ✓ |
| yeni kelime | 2 kullanımdan sonra 2. harfte ilk 3 | 0/32 | **32/32 (100)** | | ≥ 80 | ✓ |
| tüm set | Yanlış oto. düzeltme | 0,0 (855) | 0,0 (855) | 0 | değişmez | ✓ |

### Dev kullanıcıları (ayar)

| Kırılım | Metrik | Kapalı | Açık | Fark |
|---|---|---|---|---|
| tekrar (108) | İlk-3 @0 | 21,3 (13,8–27,1) | 91,7 (86,0–95,9) | +70,4 |
| tekrar | İlk-3 @1 | 29,0 | 92,9 (87,8–95,8) | +63,9 |
| tüm set (700) | KSR | 35,8 (33,1–37,8) | 47,6 (44,5–50,0) | +11,8 |
| ilk kez (592) | İlk-3 @0 | 15,2 (12,7–18,2) | 14,9 (12,4–17,8) | −0,3 |
| ilk kez | İlk-3 @3 | 51,2 | 56,4 (51,8–59,9) | +5,2 |
| ilk kez | KSR | 35,9 | 38,7 (36,0–41,1) | +2,8 |
| yeni kelime | 2. harfte ilk 3 | 0/34 | 32/34 (94,1) | |

**Kaçırılan hedef: ilk kez yazılan kelimelerde @0 testte −0,9 puan** (hedef en çok −0,5; dev'de −0,3).
561 kelimede 5 kayıp, 0 kazanç: "Kızılay'da → bir", "Elifçik → ve", "olsun → çok", "dokuzda → orada",
"saat → kaçta" (olay sayımıyla cümle başında "Bu" da 4 kez). Kullanıcı geçmişi yeniden oynatılınca
iki tür çıkıyor: bir kez görülmüş kişisel ardıllar ("razı olsun → oğlum / ellerine / kızım", "Elifçik →
bugün / okuldan") ve üç kez görülmüş ikililer (cümle başında Tolgiş, Halı, Serko). Dev'deki 3 kayıp da
aynı türden. Güven aralıkları büyük ölçüde örtüşüyor, ama nokta tahmini hedefin dışında. Test
kullanıcılarına bakılarak ayar yapılmadı.

**Denenip reddedilen: tek gözlemli ikili sınırı.** Sonraki kelimede ilk 3'ün en çok N'i, ikilisi bir
kez görülmüş (sönmüş sayı < 1,5) ve dil modelinin kendi ilk 3'ünde olmayan kişisel adaya verildi.
(Önce yalnız liste sırası değiştirildi; motor skora göre yeniden sıraladığı için etkisi olmadı, skorlar
da sırayla azalacak biçimde düzeltildi.) Kabul kuralı ölçümden önce belirlendi: dev'de tekrar @0 en çok
2 puan düşecek ve ilk kez @0 sınırsızdan (−0,3) kötü olmayacak.

| | sınır yok | N = 1 | N = 2 |
|---|---|---|---|
| dev tekrar @0 | %91,7 | %79,6 | %89,8 |
| dev ilk kez @0 farkı | −0,3 | −0,8 | −0,8 |
| test tekrar @0 | %91,0 | %82,1 | %90,3 |
| test ilk kez @0 farkı | −0,9 | −0,7 | −0,9 |

İkisi de kuralı geçemedi. Sınır, kullanıcı için doğru olan bir kez görülmüş tahminleri de itiyor
("Barışko → yine", "Akşam → Alsancak'ta", "Annem → aradı"). "İlk kez" kırılımı yalnız hedef kelimenin
yeni olup olmadığına bakıyor; kullanıcıya göre doğru kişisel adayları cezalandırıyor. Bu yüzden sınır
kaldırıldı ve −0,9 kabul edildi (Fable kararı, seçenek a). Bu denge, `eval/private`'taki gerçek metinle
yeniden değerlendirilecek.

CI eşiği (`.github/ci/personal-gate.py`) bu satırda 0,5
puanı aşınca **uyarı** verir, 1,5 puanı aşınca kırar; diğer bütün hedefler kırıcıdır.

### seed-v0 (327 vaka, tüm set)

| | Bugün (7e9533f9) | Boş depo | Öğrenme açık (tek akış) | Hedef |
|---|---|---|---|---|
| İlk-3 @0 | 20,7 (18,1–23,2) | 20,5 (18,0–23,1) | 20,3 (17,7–22,7) | açıkta düşüş ≤ 0,5 ✓ |
| İlk-3 @1 | 30,6 | 30,5 | 32,1 | ✓ |
| İlk-3 @3 | 52,3 | 52,2 | 54,9 | ✓ |
| KSR | 39,9 | 39,8 | 41,3 | ✓ |
| Aksan düzeltme | 78,2 | 78,2 | 78,2 | ✓ |
| Yanlış oto. düzeltme | 0,11 | 0,11 | 0,11 | değişmez ✓ |
| Yazım hatası ilk 3 | 71,9 | 71,9 | 71,9 | ✓ |

"Boş depo" kişisel kod yolu bakımından bugünküyle aynıdır (aynı dil modeliyle iz farkı 0). Aradaki
0,1–0,2 puan, dil modelinin yeniden derlenmesinden: sızıntı süzgeci kisisel-v0 ile örtüşen 31
korpus cümlesini daha attı (cv 82 → 104, sohbet 35 → 44). "Öğrenme açık" sütunu boş depoya göredir.

Korpusta yanlış otomatik düzeltme (`corpus_false_ac.py`, CI argümanları + `--personal`): varsayılan
8/21 687 = %0,04, `--personal` 8/21 687 = %0,04 (bugün de 8/21 687). Kişisel aday oto. düzeltme
yapmadığı ve aksan düzeltici adayı her zaman 1,0 skorla başta kaldığı için değişmesi beklenmez.

### Hız, boyut, bellek (masaüstü, JVM 21)

| | Ölçülen | Hedef |
|---|---|---|
| Öneri gecikmesi p95, dolu depo (4 983 kelime, 19 487 ikili; seed-v0) | 1,32–1,54 ms (5 koşu, ortanca 1,36) | ≤ 1,5 ms |
| aynı, boş depo | 1,07–1,17 ms | |
| Süreç içi ortalama (yalnız motor, önek sorguları) | dil modeli ~200 µs, kişisel karışım dahil ~240–300 µs | |
| Öğrenme (218 000 kelime) | ortalama 0,003–0,005 ms, p99 0,006 ms | ≤ 0,1 ms |
| Öğrenmede atma (her ~1 500 kelimede bir) | 1,5 ms (kelime), 3 ms (ikili); en çok ~10 ms | |
| Yükleme (263 KB) | 11–14 ms | ≤ 50 ms |
| Dosya, dolu depo | 263 KB | ≤ 400 KB |
| Bellek, dosyadan yüklenmiş dolu depo | ~1 330 KiB (1,36 MB) | ≤ 1,5 MB |
| Bellek, uzun öğrenmeyle dolmuş depo | ~1 500 KiB (1,54 MB) | ≤ 1,5 MB (sınırda) |

Gecikme `kboard_eval` ölçümüdür (Python ↔ motor gidiş-dönüş dahil); koşudan koşuya ±0,1 ms oynuyor,
5 koşunun biri 1,5'i aştı. Dolu depo, Common Voice + sohbet korpusunun tamamı öğretilerek
oluşturuldu (`--personal-warm`). Bellek `Runtime` farkıyla, sıkıştırılmış referanslı 64 bit JVM'de
ölçüldü; ART'ta nesne düzeni farklıdır.

## Doğrulanmayanlar ve riskler

- **Uygulama tarafı burada derlenmedi** (çevrimdışı Android eklentisi). `KBoardPersonal` taslak
  sınıflarla derlenip denetlendi; `KBoardSuggestions`, `DictionaryFacilitatorImpl`, ayar ekranı,
  manifest ve yedek kuralları ile `KBoardPersonalTest` yalnız CI'da derlenir ve koşar. Cihazda
  denenmedi: gerçek yazmada öğrenme/geri alma zamanlaması, kaydetme, ayar ekranı, yedekten hariç
  tutma (adb `bmgr` ile denenmeli).
- Öğrenme hemen yapılır, HeliBoard'unki 25 sn gecikmeli. Geri alma yalnız HeliBoard'un o 25 sn'lik
  penceresinde bekleyen kelime için çağrılır; daha geç silinen bir yazım hatası öğrenilmiş kalır
  (2 kullanım kuralı tek seferlikleri yine önerdirmez).
- Atma (eviction) model kilidini tutar: her ~1 500 kelimede bir, masaüstünde 1,5–3 ms (telefonda
  birkaç kat). O anda gelen öneri isteği bekler.
- Sentetik set kendini bilerek tekrar ediyor; gerçek kullanıcı metninde kazanç daha düşük olacaktır.
  Test seti ve sohbet korpusu aynı yöntemle (yapay zekâ) üretildi. Gerçek metinle doğrulama
  `eval/private` ile yapılmalı.
- Kişisel model dil modelinin ikilisiz bağlamlarında (önceki kelime dil modelinde yoksa) en çok
  etkili; ilk kez @0'daki kayıplar da orada.
- Dosya cihaz korumalı depoda (kilit açılmadan önce de okunabilir), HeliBoard'un kullanıcı geçmişi
  gibi. Kimlik bilgisiyle şifrelenen depoya taşımak, kilit açılmadan önce boş modelle çalışmayı ve
  sonradan birleştirmeyi gerektirir.
