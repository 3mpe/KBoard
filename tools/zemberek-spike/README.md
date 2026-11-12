# Zemberek-NLP ölçüm aracı (spike, issue #21)

Zemberek-NLP morfolojisinin **masaüstü JVM'de** açılış süresini, tutulan yığın (heap) belleğini,
en küçük çalışan `-Xmx` değerini, 1000 kelime analiz süresini ve bağımlılık boyutunu ölçer.
Sonuçlar [docs/EVALUATION.md §1](../../docs/EVALUATION.md)'e işlenir. Cihaz (ART) ölçümü ayrı
bir adımdır.

Bu klasör **bağımsız** bir Gradle projesidir (kendi `settings.gradle.kts`'i var); `app/` ve
`engine/` derlemesine dahil değildir, APK'ya hiçbir şey eklemez.

## Çalıştırma

Gerekenler: JDK 17+ ve Gradle 8.x (ağ erişimi: Maven Central + Zemberek'in kendi Maven deposu).

```sh
cd tools/zemberek-spike
gradle -q run                          # 5 koşu, rapor build/rapor.md
gradle -q run -PspikeArgs="--runs 10"  # koşu sayısını değiştir
```

## Ne ölçülüyor

`bench` modu her ölçümü **taze bir alt JVM'de** (`probe` modu) çalıştırır, böylece her koşu
gerçek bir soğuk açılıştır:

| Ölçüm | Nasıl |
|---|---|
| Sözlük yükleme | `RootLexicon.getDefault()` süresi (gömülü `lexicon.bin` + sınıf yükleme) |
| Morfoloji kurulum | `TurkishMorphology.builder().setLexicon(..).disableCache().build()` |
| Tutulan yığın | Yükleme öncesi ve sonrası `System.gc()` sonrası kullanılan heap farkı |
| En küçük `-Xmx` | 16–1024 MB arası ikili arama; yükleme + analiz OOM vermeden bitmeli |
| 1000 kelime | `eval/testset/seed-v0.jsonl` cümlelerinden kelimeler, 1000'e tamamlanana dek döngü; önbellek kapalı. İlk geçiş (JIT soğuk) ve 5 sıcak geçişin en iyisi |
| Boyut | Sınıf yolundaki jar'lar ve morfoloji jar'ının içindeki büyük kaynaklar |

Üç yürütme modu karşılaştırılır: varsayılan JIT, yalnız C1 (`-XX:TieredStopAtLevel=1`) ve
yorumlayıcı (`-Xint`, kötümser üst sınır). Android ART bunların hiçbiriyle aynı değildir; masaüstü
sayıları cihaz için yalnızca **mertebe** verir.

## Sınırlar

- Kelime listesi küçük (120 farklı kelime). Önbellek kapalı olduğu için tekrar sonucu
  bozmaz, ama nadir/uzun çekimler az temsil edilir.
- "Tutulan yığın" Java nesne yığınıdır; işlemin toplam bellek kullanımı (PSS/RSS) daha yüksektir.
- Belirsizlik giderme modeli (`tr/ambiguity/model-compressed`, ~1,7 MB) yalnızca
  `TurkishMorphology` ile yüklenmez; ölçüme dahil değildir.

Son çalıştırmanın ham çıktısı: [sonuclar/masaustu-2026-11-12.md](sonuclar/masaustu-2026-11-12.md).
