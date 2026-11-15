# Adım 2: öneri sıralaması, cümle başı tamamlama (2026-11-15)

Öneri kalitesi planının ikinci adımı. Hedefler (adım 1'in üstüne, seed-v0 dev yarısı): İlk-3 @3 en az
+5 puan, @1 en az +3 puan. Bütün kalite sayıları masaüstünde, ölçüm modeliyle (`kboard-tr.kbn`) alındı.

## 1. Kaçan öneriler nerede?

Adım 1 modeliyle dev yarısında @1'de kaçan hedefler (puan, 593 kelimenin yüzdesi):

| Sınıf | @1 | @2 | @3 |
|---|---|---|---|
| cümle ortası, hedef ancak 4+ harf sonra ilk 3'te | 23,6 | 15,3 | 9,6 |
| cümle ortası, 1-2 harf sonra | 15,6 | 18,5 | 14,0 |
| cümle ortası, 4.-5. sırada | 3,5 | 3,2 | 5,6 |
| cümle başı (ilk kelime), sonradan ya da 4.-5. sırada bulunan | 19,1 | 16,0 | 9,8 |
| hiç bulunmayan | 4,2 | 3,7 | 3,2 |

Cümle başındaki kayıpların nedeni: `build_ngram.py` her bigram satırını 60 ardılla sınırlıyordu, cümle
başı (`<s>`) satırı da dahil. 60'ın dışında kalan cümle başı kelimeleri tek kelime olasılığına geri
çekiliyordu ("Ka" → Kadar, Kaldı, Karşı; "Kanka", "Kahve" yok).

Motor ablasyonları (yazım hatası, ek zinciri, yazım kuralları kapalı) @1 ve @3'ü değiştirmiyor: kalan
kayıplar dil modelinin sıralamasıdır.

## 2. Değişiklik

- `build_ngram.py --max-succ-bos N`: yalnız `<s>` satırının ardıl sınırı. İki model görevi 5000 kullanır
  (100 000 ile sonuç aynı). Ölçüm modeli 1 401 966 → 1 419 672 bayt, dağıtılan 1 418 473 → 1 436 176 bayt.
- `NgramModel`: 64 kayıttan uzun satırlarda arama ikili (kayıt sıraları id'ye göre sıralanır). Doğrusal
  aramayla 5000 kayıtlı satır gecikmeyi p95 1,1 → 3,0 ms'ye çıkarıyordu; ikili aramayla öneriler bayt
  bayt aynı, p95 1,1 ms. Birim testi: `NgramModelBigRowTest`.

## 3. Sonuç (ölçüm modeli; önce = adım 1, main `bdcb0502`)

| Küme | | @0 | @1 | @2 | @3 | KSR |
|---|---|---|---|---|---|---|
| seed-v0 dev | önce | 23,9 | 34,1 | 43,4 | 58,0 | 44,1 |
| | sonra | 23,9 | 34,2 | 44,3 | 61,2 | 45,1 |
| seed-v0 test | önce | 22,5 | 34,3 | 43,6 | 55,3 | 43,2 |
| | sonra | 22,5 | 34,9 | 43,9 | 56,8 | 44,2 |
| taze sohbet (110) | önce | 33,3 | 47,7 | 56,5 | 67,7 | 51,2 |
| | sonra | 33,3 | 48,0 | 59,3 | 71,3 | 52,8 |
| taze doğal (300) | önce | 15,5 | 31,8 | 42,1 | 51,6 | 37,2 |
| | sonra | 15,5 | 31,7 | 42,5 | 51,8 | 37,3 |

Aksan düzeltme (dev %92,5) ve yanlış otomatik düzeltme (dev 1/434) değişmedi.

**Hedefler tutmadı:** @3 +3,2 (hedef +5), @1 +0,1 (hedef +3). Taze sohbet kazancı dev kazancından büyük
(+3,6), taze doğal bekçi sınırı içinde.

## 4. Denenip alınmayanlar (dev, hepsi ±0,6 puan içinde; 1 kelime = 0,2 puan)

| Deneme | @0 | @1 | @2 | @3 | KSR |
|---|---|---|---|---|---|
| Kneser-Ney indirimi 0,6 / 0,85 / 0,95 | 23,9 / 24,1 / 24,1 | 34,1 / 34,6 / 34,4 | 44,3 / 44,3 / 44,1 | 61,2 / 61,2 / 61,4 | 45,0 / 45,1 / 45,1 |
| tek kelime olasılığına ham sıklık %30 / %60 / %90 | 23,7 / 23,7 / 23,7 | 34,2 / 34,2 / 34,1 | 44,5 / 44,6 / 44,3 | 61,2 / 61,8 / 61,8 | 45,2 / 45,5 / 45,5 |
| `--min-tri 1` | 24,1 | 34,6 | 44,6 | 61,6 | 45,2 |
| `--max-succ 120` | 23,9 | 34,1 | 44,5 | 60,8 | 45,0 |

Ölçüm notu: HeliBoard yazarken yazılan kelimeyi öneri çubuğunda göstermez, ölçüm aracı ise motorun
yazılanı tekrar eden adayını ilk 3'te sayar. Bu aday izlerden çıkarılınca dev @3 yalnız +0,4, @1 0
değişiyor; ölçüt değiştirilmedi.

## 5. Güvenlik (dağıtılan model, süzgeçsiz; 1 436 176 bayt)

seed-v0 yanlış oto. düzeltme %0,11 (1/904); korpus %0,10 / %0,10; kural tuzakları 0/229; de/da/ki
otomatik düzeltme 0, tuzakta ilk yuva %0,0, ayrım ilk 3'te %77,8, ilk yuvada %57,4; p95 1,25 ms; günlük
ifadeler ölçüm 38/45, dağıtılan 40/45, sabit 3/3. Kişisel öğrenme: "ilk kez @0 farkı" −1,4 (koruma −1,5,
adım 1 ile aynı).
