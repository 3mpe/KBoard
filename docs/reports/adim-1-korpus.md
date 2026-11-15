# Adım 1: sohbet korpusunu büyütme (2026-11-15)

Öneri kalitesi planının ilk adımı: dil modelinin korpusunu büyütmek. Bütün kalite sayıları masaüstünde,
ölçüm modeliyle (`kboard-tr.kbn`, test cümleleri süzülmüş) alındı. Kararlar Fable'ındır.

## 1. Hangi korpus? İki deney

Taban (dev yarısı, 157 vaka): İlk-3 @0 %21,0 · @1 %30,9 · @2 %38,8 · @3 %53,0 · KSR %40,4; model 1,07 MB.

**A. Vikipedi cümleleri.** Common Voice `server/data/tr/wiki.tr.txt` (348 494 cümle, CC0, upstream
`bd860fa0`) dil modeline eklendi:

| Ek | @0 | @1 | @2 | @3 | KSR | Boyut |
|---|---|---|---|---|---|---|
| yok (taban) | 21,0 | 30,9 | 38,8 | 53,0 | 40,4 | 1,07 MB |
| +48 000 cümle | 21,4 | 29,3 | 35,5 | 48,2 | 38,4 | 2,99 MB |
| +100 000 | 20,7 | 28,3 | 33,6 | 47,2 | 37,7 | 4,39 MB |
| +348 494 | 18,9 | 24,8 | 29,7 | 44,8 | 35,2 | 9,20 MB |
| +48 000, cv ×10, sohbet ×30 | 21,6 | 32,2 | 36,6 | 50,4 | 39,3 | 4,04 MB |
| +348 494, cv ×10, sohbet ×30, budanmış (10/10/20) | 21,2 | 29,5 | 32,5 | 48,4 | 37,1 | 1,46 MB |

Her biçimde @3 ve KSR düşüyor. **Karar: alınmadı.** Yeni kanıt olmadan yeniden açılmaz; ileride yalnız
kelime dağarcığı kaynağı olarak düşünülebilir.

**B. Eğriyi hangi korpus taşıyor?** (cv = Common Voice 47 781 cümle, sohbet = `sohbet-v1` 2 801 mesaj ×3)

| cv | sohbet | @0 | @1 | @2 | @3 | KSR |
|---|---|---|---|---|---|---|
| %100 | %100 | 21,0 | 30,9 | 38,8 | 53,0 | 40,4 |
| %50 | %100 | 21,0 | 30,2 | 38,8 | 55,0 | 40,9 |
| %25 | %100 | 20,3 | 29,5 | 39,7 | 55,2 | 41,1 |
| %100 | %50 | 16,9 | 26,1 | 33,0 | 47,2 | 36,6 |
| %100 | %25 | 13,9 | 23,4 | 31,3 | 44,4 | 34,4 |

Öğrenme eğrisinin tamamı sohbet korpusundan geliyor. **Karar: sohbet korpusu büyütülür**
(`data/sohbet/sohbet-v2.txt`, ayrıntı `data/sohbet/README.md`). Common Voice ağırlığı bu adımda
değiştirilmedi (iki etki ayrılabilsin diye).

## 2. Taze ölçüm kümeleri

seed-v0 ile sohbet korpusu aynı yöntemle (yapay zekâ) yazıldığı için kazancın bir kısmı üslup eşleşmesi
olabilir. Bu yüzden üzerinde hiç ayar yapılmayan iki küme eklendi:

- `eval/testset/taze-sohbet-v0.jsonl`: 110 mesaj, **sentetik**; seed-v0, korpus ve motor çıktısını görmemiş
  ayrı bir oturumda yazıldı; depo sahibi 20 mesajlık örneklemi onayladı. (Aynı partinin 40 mesajı
  `taze-sohbet-tablet-v0.jsonl`: yalnız tablet karşılaştırmasında kullanılır.)
- `eval/testset/taze-dogal-v0.jsonl`: 300 cümle, Common Voice'tan sabit tohumla; insan yazımı ama okuma
  metni üslubu. İkincil: hedef değil bekçi (İlk-3 @3 ve KSR en çok 1 puan düşebilir).

Aşırı uyum ölçütü (Fable): taze sohbet kümesindeki kazanç aynı işaretli olmalı; @0 ya da @3'te dev
kazancının üçte birinden azsa sonuç üsluba aşırı uyum sayılır.

## 3. Sonuç

Önce = `sohbet-v1` ile, taze kümeler süzülmüş (aynı süzgeç, yalnız korpus farkı). Sonra = `sohbet-v1` +
`sohbet-v2` (6 281 mesaj), ikisi de ×3.

| Küme | | @0 | @1 | @2 | @3 | KSR |
|---|---|---|---|---|---|---|
| seed-v0 dev (596 kelime) | önce | 20,5 | 30,5 | 38,5 | 52,8 | 40,1 |
| | sonra | **23,9** | 34,1 | 43,4 | **58,0** | **44,1** |
| seed-v0 test (654) | önce | 19,6 | 29,8 | 37,8 | 51,4 | 39,7 |
| | sonra | 22,5 | 34,3 | 43,6 | 55,3 | 43,2 |
| taze sohbet (615) | önce | 27,1 | 39,4 | 46,5 | 60,2 | 44,8 |
| | sonra | **33,3** | 47,7 | 56,5 | **67,7** | **51,2** |
| taze doğal (1 630) | önce | 15,6 | 32,5 | 42,5 | 51,8 | 37,5 |
| | sonra | 15,5 | 31,8 | 42,1 | 51,6 | 37,2 |

- Hedefler (dev): @3 ≥ %56 ✓ (58,0), KSR ≥ +2 puan ✓ (+4,0), **@0 ≥ %24 ✗ (23,9; 0,1 puan eksik)**.
- Taze sohbet kazancı dev kazancından büyük (@0 +6,2'ye karşı +3,4; @3 +7,5'e karşı +5,2): aşırı uyum
  işareti yok.
- Taze doğal: @3 −0,2, KSR −0,3 (bekçi sınırı 1 puan içinde).
- Aksan düzeltme (seed-v0 tümü) %90,0 → %91,7; yanlış otomatik düzeltme %0,11 (1/904) aynı.
- Ara ölçüm: ilk 8 grup (3 142 mesaj) ile dev 22,3 / 56,6 / 43,0, taze sohbet 31,3 / 63,6 / 49,2;
  sohbet ağırlığı 2–8 arasında sonuç değişmiyor (ağırlık 3 kaldı).

## 4. Güvenlik (dağıtılan model, süzgeçsiz; 1 418 473 bayt)

| Denetim | Sonuç | Eşik |
|---|---|---|
| seed-v0 yanlış otomatik düzeltme | %0,11 (1/904) | ≤ %0,3 |
| korpus yanlış otomatik düzeltme (bağlamsız / bağlamlı) | %0,10 / %0,10 | ≤ %0,3 |
| kural-v0 tuzaklarında düzeltme | 0/229 | 0 |
| kural kesinliği / liste duyarlılığı / soru eki | %100 / %98,9 / %96,7 | ≥ %99 / %95 / %85 |
| de/da/ki: ayrım otomatik düzeltme | 0 | 0 |
| de/da/ki: tuzakta ayrım ilk yuvada | %0,0 | ≤ %10 |
| korpusta ayrık bağlaç ilk yuvada (ölçüm modeli) | %0,09 (1/1 164) | ≤ %5 |
| gecikme p95 | 1,14 ms (önce 0,81–1,2 ms) | ≤ 5 ms |
| boyut | 1,42 MB (önce 1,08 MB) | ≤ 2 MB (bu adımda 1,1 MB'tan çıkarıldı) |
| günlük ifade listesi (ölçüm / dağıtılan) | 38/45 / 40/45 (önce 36/45 / 39/45); sabit 3/3 | sabit satırlar dağıtılan modelde zorunlu |

Kişisel öğrenme eşikleri geçti; "ilk kez İlk-3 @0 farkı" −1,4 puan (koruma eşiği −1,5; önceki ölçüm −0,9):
taban model güçlendiği için öğrenmenin ilk kullanımda eklediği pay küçüldü. Uyarı olarak izlenir.

## 5. Yapılmayanlar

- Yükleme süresi cihazda ölçülmedi (emülatör/tablet koşusu birleştirmeden sonra).
- `data/suffix/chains-tr.tsv` yeni korpusla yeniden üretilmedi (ayrı değişiklik; etkiler ayrılsın diye).
- Common Voice ağırlığı/boyutu değişmedi (B deneyi yarıya indirmenin zarar vermediğini gösteriyor; taze
  kümelerle ayrıca ölçülecek).
- `sohbet-v2`'nin tamamı insan gözünden geçmedi (80 satırlık örneklem okundu).
