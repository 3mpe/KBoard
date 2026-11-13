# Sohbet cümleleri (`sohbet-v1.txt`)

KBoard'un sonraki kelime modelini sohbet diline alıştırmak için yazılmış, mesajlaşma tarzında
Türkçe cümleler. Arkadaşa, aileye, iş arkadaşına WhatsApp benzeri uygulamalarda yazılan kısa
mesajları örnek alır. Dosyanın amacı, açık lisanslı kaynaklarda (Tatoeba, Vikipedi, ParlaMint)
neredeyse hiç bulunmayan günlük yazışma dilini modele kazandırmaktır (bkz. `data/KAYNAKLAR.md`,
Faz 4, #26).

## Nasıl üretildi

- Bütün cümleler bu proje için **sıfırdan yazıldı**; yapay zekâ destekli olarak hazırlandı ve
  elle gözden geçirildi.
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
