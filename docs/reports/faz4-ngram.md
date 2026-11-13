# Faz 4: n-gram dil modeli (sonraki kelime + bağlama göre tamamlama)

Tarih: 2026-11-13. Ölçüm: `eval/testset/seed-v0.jsonl` (327 vaka, 1 250 kelime), masaüstü motor
(`engine` CLI, subprocess öngörücü). Vakalar `crc32(id)` ile ikiye ayrıldı: **dev** (157 vaka, 596
kelime) yalnız ayar için, **test** (170 vaka, 654 kelime) yalnız rapor için.

## Model

- Korpus: Common Voice tr `sentence-collector.txt` (47 781 satır, CC0). Test setindeki herhangi bir
  vakayla normalize 4 kelimelik ortak dizisi olan 82 cümle atıldı (kısa vakalarda tam alt dize).
- Araya katmalı Kneser-Ney (D = 0,75): bigram (sayı ≥ 1) + trigram (sayı ≥ 2), bağlam başına en
  çok 60 ardıl, cümle başı `<s>`. Sözlük 34 211 kelime; her kelime en sık yazılışıyla gösterilir
  ("ankara" → "Ankara").
- Öneri yasağı (`data/ngram-denylist.txt`): küfürler, OCR hataları ("Haşan", "bazan"), eski yazımlar
  ("candarma") ve romanlardaki karakter adları modelde bağlam olarak kalır ama hiç önerilmez (64 kelime).
- Dosya `kboard-tr.kbn` (biçim 2): 2,03 MB (APK içinde sıkıştırılmış ~0,83 MB). Derleme her koşuda
  aynı baytı üretir. JVM'de yükleme ~0,5 s, bellek ~12 MB; kendi iş parçacığında yüklenir, aksan
  düzelticiyi bekletmez. CI bütçesi şimdilik ≤ 2,1 MB.
- Bağlam bir virgül ya da başka bir ayraçta kesildiyse cümle başı sayılmaz: `<s>` yerine bilinmeyen
  bağlam (tek kelime olasılığı) kullanılır, öneri büyük harfle başlamaz.
- `NgramSource`: önek boşsa bağlamın en olası ardılları; önek varsa önekle başlayan ve yazılan
  aksanlarla uyumlu model kelimeleri, `0,95 + 0,05 · log10 P(w | son iki kelime)` skoruyla. Hiçbir
  aday otomatik düzeltme yapmaz; boşluk davranışı değişmez.

## Ayar (yalnız dev)

| Değişken | Denenen | Seçilen | dev İlk-3 @3 |
|---|---|---|---|
| skor eşlemesi (offset, eğim) | (0,9–1,3; 0,03–0,15) | (0,95; 0,05) | %40,4 (0,1 eğimde %38,6) |
| min-bi / min-tri / min-uni | 2/3/2, 1/2/2, 1/2/1 | 1/2/1 | %37,3 → %39,8 → %42,8 |
| bağlam başına ardıl | 30, 60 | 60 | %42,2 / %42,8 |

## Sonuç (test yarısı, 200 örnekli bootstrap %95 güven aralığı)

| | Önce | Sonra | %95 GA (sonra) | Hedef (Fable) |
|---|---|---|---|---|
| Sonraki kelime İlk-3 @0 | %0,0 | **%11,8** | %9,2 – %14,5 | ≥ %8 |
| İlk-3 @1 | %8,7 | **%21,9** | %18,4 – %25,6 | ≥ %15 |
| İlk-3 @2 | %11,9 | %27,6 | | |
| İlk-3 @3 | %24,4 | **%42,3** | %38,5 – %45,8 | ≥ %31 |
| KSR (tuş tasarrufu) | %17,9 | **%31,5** | %29,4 – %33,3 | ≥ %23 |
| Aksan düzeltme | %78,8 | %78,8 | %72,7 – %84,6 | değişmemeli |
| Yanlış oto. düzeltme | %0,0 | %0,0 | | ≤ %0,3 |
| Gecikme p95 (tüm tuşlar) | 0,52 ms | 0,75 ms | | ≤ 1,5 ms |

Kategoriye göre (test, İlk-3 @0 / @3 / KSR, önce → sonra):

| Kategori | Kelime | @0 | @3 | KSR |
|---|---|---|---|---|
| sohbet | 329 | %0 → %13,2 | %22,4 → %43,8 | %17,5 → %33,2 |
| dogru_yazim | 209 | %0 → %10,9 | %27,3 → %44,1 | %17,4 → %29,4 |
| kullanici_sozlugu | 101 | %0 → %6,8 | %22,8 → %32,6 | %20,7 → %29,1 |
| ozel_isim | 15 | %0 → %25,0 | %41,7 → %58,3 | %14,1 → %38,4 |

Tüm set (1 250 kelime): @0 %11,5, @3 %42,5, KSR %31,5, aksan %78,2, yanlış düzeltme %0,1. CI eşiğine
eklenenler (ulaşılanın ~2 puan altı): @0 ≥ %9,5, @1 ≥ %19, @3 ≥ %40, KSR ≥ %29,5.

## Sınırlar ve sıradakiler

- Korpus okunmak üzere yazılmış cümleler; mesajlaşma dili (kısaltma, argo) zayıf. Elle yazılan
  sohbet korpusu ayrı modelle araya katılacak.
- Biçim 3 (sohbet modeli araya katılmadan önce zorunlu): kelime/gösterim tekrarını kaldırma, varint
  id, 8 bit log-olasılık, `HashMap` yerine düz sıralı diziler. Hedef ≤ 1 MB dosya, ≤ 4 MB bellek.
- Ölçüm masaüstünde; telefonda tek harfli önek gecikmesi (12 000 adaya kadar tarama) ölçülecek.
- Uygulamada motor yüklüyken HeliBoard'un kendi yazım hatası düzeltmesinin hâlâ çalıştığını sınayan
  test eklenecek (motor tamamlamaları ilk sıraya çıkıp onu bastırabilir).
- Aksan düzeltme henüz bağlamı kullanmıyor ("sık/şık" belirsizliği); sıradaki adım dil modeliyle
  bağlama göre aksan seçimi.
