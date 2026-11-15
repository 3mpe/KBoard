# Adım 3: iki hatalı kelimelerde öneri (2026-11-15)

Öneri kalitesi planının üçüncü adımı: iki harfi yanlış yazılmış kelimelerde doğrusu öneri çubuğunda
çıksın. Hedef: iki hatalı yazımda doğrusu ilk 3'te en az %55, yeni otomatik düzeltme yok (yalnız öneri).

## Ölçüm

`typoprobe` yoklaması: seed-v0'daki 4+ harfli kelimeler, betikle üretilmiş yazım hataları (sabit tohum
42), ölçüm modeli. Ayar yalnız dev yarısında yapıldı, test yarısı rapor için. "İki hatalı" = "iki komşu
hata" ile "eksik + komşu" sınıflarının ortalaması.

## Neden kaçıyordu?

`TypoSource` uzaklık 2 aramasını yalnız 6+ harfli yazımlarda yapıyordu. seed-v0 kelimelerinin %60'ı 6
harf ya da daha kısa; bir harfi eksik yazılınca (eksik + komşu) yazım 3-5 harfe iniyor ve hiç aranmıyordu.

## Değişiklik

- `distance2MinLength` 6 → 4.
- `distance2Penalty` = 1,0: yalnız uzaklık 2 ile bulunan adaylar sıralamada 1,0 puan geride. Cezasız
  denemede tek hatalı sınıflar 1-2 puan düşüyordu (uzaklık 2 adayları uzaklık 1 doğrularını ilk 3'ten
  itiyordu); ceza 1,0 ile hiçbir tek hatalı sınıf düşmüyor.
- Otomatik düzeltme kuralı değişmedi: yalnız uzaklık 2 ile bulunan adaya oto. düzeltme 8+ harfte ve
  yazım hatası oto. düzeltmesi zaten kapalı.
- Ölçüm aracı: `--typo-d2 n[,probes]`, `--typo-d2-penalty p`.

## Sonuç (doğrusu ilk 3'te)

| Hata türü | dev önce | dev sonra | test önce | test sonra |
|---|---|---|---|---|
| komşu tuş (1 hata) | 83,2 | 83,2 | 84,5 | 85,1 |
| eksik harf | 71,2 | 71,6 | 70,4 | 70,8 |
| fazla harf | 87,6 | 87,8 | 86,4 | 86,6 |
| çift harf | 86,4 | 86,4 | 85,8 | 86,6 |
| yer değiştirme | 83,9 | 83,9 | 81,8 | 81,4 |
| uzak harf | 77,2 | 78,0 | 78,4 | 79,0 |
| **iki komşu hata** | 69,2 | 76,4 | 70,9 | 77,8 |
| **eksik + komşu** | 25,4 | 40,6 | 26,8 | 41,5 |
| aksansız + komşu | 83,6 | 83,9 | 83,2 | 83,6 |
| **iki hatalı (ortalama)** | 47,3 | 58,5 | 48,9 | 59,7 |

Hedef (%55) dev ve test yarısında tuttu. Boşlukta doğru/yanlış düzeltme oranları her sınıfta aynı kaldı
(yeni otomatik düzeltme yok). Test yarısındaki tek düşüş yer değiştirmede −0,4 (2 kelime).

Denenen cezalar (dev, iki komşu / eksik + komşu / komşu tuş): 0 → 77,0 / 46,6 / 81,2; 0,3 → 77,0 / 44,0 /
82,4; 0,6 → 76,8 / 42,2 / 82,8; 1,0 → 76,4 / 40,6 / 83,2. Uzaklık 2 eşiği 5: 75,4 / 36,4 / 82,0.
Arama bütçesi 1500 → 3000: fark yok.

## Güvenlik ve gecikme

seed-v0 ve taze kümelerin kalite sayıları adım 2 ile aynı (dev @3 %61,2, yanlış oto. düzeltme 1/434);
bütün eşikler geçti. Gecikme p95 (masaüstü): seed-v0 1,26 → 1,47 ms, taze sohbet 1,64 ms, taze doğal 1,21 ms.

Bilinen sınır: hedefi aksan sözlüğünde olmayan kelimeler (dev'de %12, ör. "hallederim", "annemlerle")
uzaklık 1-2 aramasıyla bulunamaz; bu adımda ele alınmadı.
