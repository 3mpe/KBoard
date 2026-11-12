# Teknik değerlendirmeler

> Aşağıdaki sayılar ön tahmindir; kesin değerler Faz 3 öncesindeki ölçüm çalışmasında
> (spike) referans cihazda ölçülecek. Lisanslar kullanılmadan önce kaynağından doğrulanacak.

## 1. Zemberek-NLP mobilde kullanılabilir mi?

Zemberek-NLP (Java, Apache-2.0, GPL-3.0 ile uyumlu) Türkçe için en kapsamlı açık morfolojik
analiz/üretim kütüphanesi. Sorun "doğru mu" değil, "klavye sürecinde taşınabilir mi".

| Boyut | Beklenti | Klavye için anlamı |
|---|---|---|
| Boyut | morfoloji modülü + gömülü sözlük birkaç on MB mertebesinde (jar + kaynaklar) | APK'ya doğrudan eklemek pahalı |
| Açılış | `TurkishMorphology.createWithDefaults()` sözlüğü yükleyip ek grafiğini kurar; masaüstünde saniye mertebesi | IME her açılışta bunu bekleyemez |
| Bellek | onlarca–yüzlerce MB yığın (heap) | Android IME süreçleri düşük bellekte ilk öldürülenlerdendir |
| Hız | tek kelime analizi hızlı, ama önekten "olası tamamlamaları üretme" doğrudan desteklenmiyor | tamamlama için ayrı yapı gerekir |
| Java 8 / Guava vb. bağımlılıklar | Android'de çalışır ama minify/desugar ayarı ister | ek bakım yükü |

**Öneri: Zemberek'i cihazda değil, masaüstünde ön hesaplama aracı olarak kullan.**

1. `data/` betikleri Zemberek ile korpustaki kökler için sık çekimleri üretir, frekansa göre budar
   ve sıkıştırılmış bir trie/FST'ye (veya `makedict` ile HeliBoard'un ikili sözlük formatına) yazar.
2. Cihazda hafif bir **ek üretici** (Kotlin, `engine/morph`) yalnızca kişisel sözlükteki kökler ve
   sözlük dışı kökler için çalışır: ünlü uyumu (büyük/küçük), ünsüz yumuşaması (`kitap→kitabı`),
   ünsüz benzeşmesi (`-de/-te`), kaynaştırma harfleri, özel isimlerde kesme işareti. Sık ~200 ek
   dizisiyle sınırlı tutulur; kural tabloları Zemberek'in morfotaktiğinden türetilir.
3. Zemberek'in kendisi gerekirse "lazy-load" arka plan iş parçacığında, yalnızca yüksek bellekli
   cihazlarda isteğe bağlı açılabilir (varsayılan kapalı).

Spike görevi (Faz 3 başında): Zemberek'i referans cihazda açılış süresi, yığın kullanımı ve
1000 kelime analiz süresiyle ölçmek; sonuç bu bölüme yazılacak.

## 2. Deasciifier seçenekleri

| Seçenek | Nasıl çalışır | Artı | Eksi |
|---|---|---|---|
| A. Yuret desen tabanlı (Emacs `turkish-mode`; Python/JS/Java portları, Zemberek normalizasyon modülünde de bir portu olduğu biliniyor) | Her belirsiz harf için çevresindeki karakter desenlerine bakan karar listeleri | Sözlük gerektirmez, OOV'da da çalışır, hızlı, literatürde kelime düzeyinde yüksek doğruluk | Bağlamı yalnızca karakter penceresiyle görür; `sik/sık/şık` gibi kelime düzeyi belirsizlikte zayıf |
| B. Sözlük + bağlam | Sözlükteki her kelimenin ascii-katlanmış anahtarı → aday formlar; bağlam n-gram skoruyla seçim | Klavye öneri hattına doğal oturur, sonraki kelime modeliyle aynı veriyi kullanır | Sözlükte olmayan formlarda morfolojiye/A'ya ihtiyaç duyar |
| C. Küçük sinir ağı (karakter düzeyi) | Dizi etiketleme | En yüksek tavan | Model boyutu, gecikme, eğitim verisi; Faz 4 sonrası |

Lisans notu: proje GPL-3.0 olduğu için GPL, MIT ve Apache-2.0 lisanslı portların hepsi uyumlu;
yine de her portun lisansı kaynağından doğrulanıp `data/KAYNAKLAR.md`'ye yazılacak.

**Öneri: B birincil, A yedek.** Sıralayıcı, B'nin adaylarını bağlamla puanlar; sözlükte aday yoksa
A'nın çıktısı aday olarak eklenir. Önemli: AOSP/HeliBoard native motoru aksanlı harfleri temel
harfe yakın sayıyor olabilir (`cok` yazınca `çok` önerisi). Faz 0 baseline'ı bunu ölçecek; Faz 2'nin
hedefi bunun üzerine çıkmak.

## 3. Sonraki kelime modeli (Faz 4 için not)

- İlk adım kuantize, budanmış 3-gram (Kneser-Ney), HeliBoard'un mevcut bigram altyapısına ek olarak.
- Sohbet dili verisi kritik; açık lisanslı Türkçe sohbet verisi kıt. Seçenekler ve lisansları Faz 4
  öncesi `data/KAYNAKLAR.md`'de listelenecek. Kişisel öğrenme bu açığı zamanla kapatır.
