# Sohbet cümleleri (`sohbet-v1.txt`, `sohbet-v2.txt`)

KBoard'un sonraki kelime modelini sohbet diline alıştırmak için yazılmış, mesajlaşma tarzında
Türkçe cümleler. Arkadaşa, aileye, iş arkadaşına WhatsApp benzeri uygulamalarda yazılan kısa
mesajları örnek alır. Dosyanın amacı, açık lisanslı kaynaklarda (Tatoeba, Vikipedi, ParlaMint)
neredeyse hiç bulunmayan günlük yazışma dilini modele kazandırmaktır (bkz. `data/KAYNAKLAR.md`,
Faz 4, #26).

## Nasıl üretildi

- Bütün cümleler bu proje için **yapay zekâ ile üretildi ve insan gözden geçirdi** (sentetik metin;
  kullanım kararı ve koşulları: `data/KAYNAKLAR.md`, "Sentetik sohbet metni").
- Hiçbir şarkı, kitap, altyazı, web sitesi, forum veya sosyal medya metninden alıntı ya da
  kopya yoktur. Gerçek kişilere ait kişisel veri (telefon, adres, IBAN vb.) içermez; geçen
  adlar (Ayşe, Mehmet, Kerem...) yaygın Türkçe adlardır ve kimseyi işaret etmez.
- `eval/testset/` dosyalarına bakılmadan yazıldı (değerlendirme setine sızıntı olmasın diye).
- Denetim: `python3 -I data/tools/sohbet_check.py` satır, kelime, ortanca uzunluk, tekrar ve
  en sık ilk dört kelimelik başlangıçları yazdırır; sorun bulursa 1 ile çıkar.

## Lisans

[CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/deed.tr). Metin kamu malına
adanmıştır; atıf zorunluluğu yoktur. (Uygulama kodu GPL-3.0'dır; bu veri dosyası ondan ayrıdır.)

## Biçim ve sayılar

- UTF-8, satır başına bir mesaj, boş satır yok.
- 2 801 satır, 19 006 kelime, 6 151 farklı kelime biçimi.
- Mesaj başına 2–15 kelime, ortanca 7.
- Tam tekrar yok; aynı ilk dört kelimeyle başlayan en fazla 2 satır var.

## Üslup notları

- Çoğunluk standart yazımdır: Türkçe harfler (ç ğ ı İ ö ş ü) doğru, soru eki ayrı yazılır
  (`gelir misin`, `var mı`), özel adlara gelen ekler kesme işaretiyle ayrılır
  (`Ayşe'ye`, `Ankara'dan`, `Kadıköy'de`).
- Konular: buluşma ve ulaşım, yemek ve alışveriş, iş ve okul, sağlık, aile, hava, spor ve
  hobiler, tatil, ev işleri, faturalar ve resmi işler; teşekkür, özür, tebrik, kısa taziye.
- Kipler: emir (`gelirken ekmek al`), gelecek (`yarın arayacağım`), şimdiki zaman
  (`yoldayım, geliyorum`), geçmiş anlatım, `-ebilir misin` ricaları.
- Saat ve sayılar az ve yazışmadaki gibi: `saat 8'de`, `ayın 15'inde`.
- Yaklaşık %10 gayriresmî sohbet dili: `slm`, `mrb`, `nbr`/`naber`, `tmm`, `eyw`, `kib`,
  `abi`/`abla`/`kanka`, `bi`, `geliyom`, `napıyosun` gibi konuşma yazımları; çoğunlukla küçük
  harfle ve noktalamasız. Bunlar bilinçli olarak azınlıkta tutuldu; modelin önereceği biçim
  olarak standart yazım baskın kalmalıdır.
- Hakaret, argo küfür veya kırıcı içerik yoktur.

## `sohbet-v2.txt` (adım 1, 2026-11-15)

Dil modelinin öğrenme eğrisini sohbet korpusu taşıdığı için (bkz. `docs/reports/adim-1-korpus.md`)
korpus büyütüldü. 6 281 mesaj, 34 764 kelime, 9 578 farklı kelime biçimi; mesaj başına ortanca 6 kelime.

- **Yapay zekâ ile üretildi; 80 satırlık rastgele bir örneklem okundu, tamamı insan gözünden
  geçmedi.** CC0-1.0.
- 16 ayrı yazım oturumu, her biri 400 mesaj ve farklı bir konuşan grubu: (1) üniversite öğrencileri,
  (2) anne babalar, (3) iş yazışması (siz dili), (4) lise çağı, (5) 55 yaş üstü, (6) tanımadığı
  kişilere yazanlar (ev sahibi, kurye, usta, randevu), (7) çiftler ve yakın arkadaşlar, (8) hobi ve
  etkinlik grupları, (9) selamlaşma, onay, iyi dilek gibi kısa kalıplar, (10) kardeşler ve kuzenler,
  (11) esnaf ve müşteri, (12) yolda olanlar, (13) uzaktaki arkadaşlar ve gurbet, (14) ev hali,
  (15) fikir, tavsiye ve gün anlatımı, (16) resmi işler ve gündelik ödemeler.
- Yazım oturumları `sohbet-v1.txt`'yi, `eval/testset/` dosyalarını, taze ölçüm kümelerini ve motor
  çıktısını görmedi; yalnız konu listesi ve bu dosyadaki üslup kuralları verildi.
- Süzgeç (6 400 satırdan 119'u atıldı): kendi içinde ve `sohbet-v1` ile normalize tekrar (34), taze
  sohbet kümesiyle 4-gram örtüşmesi (33), `data/ngram-denylist.txt` kelimesi geçen satır (46; çoğu
  "geliyom", "bişey" gibi konuşma yazımları), beş ve daha uzun rakam dizisi, adres ya da bağlantı (0),
  aynı ilk dört kelimeyle üçüncü satır (0), yanlış yazım listesindeki bir biçimi içeren satır (6: sağol, haftasonu, iyiki).
- Gerçek kişi, telefon, adres, hesap adı, marka yok; adlar yaygın Türkçe adlardır.
- Denetim: `python3 -I data/tools/sohbet_check.py data/sohbet/sohbet-v2.txt`.
