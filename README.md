# KBoard

Türkçe için en iyi otomatik tamamlama, aksan düzeltme ve sonraki kelime tahminini hedefleyen,
HeliBoard tabanlı, **yalnızca Türkçe** ve tamamen çevrimdışı bir Android klavyesi.

- Gizlilik: internet izni yok, tüm işlem cihazda.
- Sesle yazma: öneri şeridinin sağındaki mikrofon, telefonda kurulu sesle yazma uygulamasına (ör. Google)
  geçer. KBoard sesi kendisi dinlemez, mikrofon ya da internet izni yoktur. Sesi o uygulama işler; kendi
  izinleri ve internet kullanımı o uygulamanın ayarlarına bağlıdır. İlk dokunuşta bu kısa bir bildirimle
  söylenir, ikinci dokunuşta geçilir. Parola ve e-posta alanlarında ve kurulu sesle yazma uygulaması yoksa
  mikrofon görünmez; araç çubuğu ayarlarından sabitlemesi kaldırılabilir.
- Taban: [HeliBoard](https://github.com/HeliBorg/HeliBoard) forku (GPL-3.0). Upstream'e katkı gönderilmez.
- Durum: **Faz 0, ölçüm altyapısı.** HeliBoard kodu Faz 1'de depoya alınacak.

## Belgeler

- [Proje planı](docs/PLAN.md): depo yapısı, geliştirme ortamı, öneri hattı bağlantı noktaları, Faz 0–1 adımları
- [Açık sorular](docs/OPEN_QUESTIONS.md)
- [Zemberek ve deasciifier değerlendirmesi](docs/EVALUATION.md)

## Dizinler

| Dizin | İçerik |
|---|---|
| `eval/` | Ölçüm aracı (Python, standart kütüphane) ve test seti |
| `engine/` | Öneri motoru (saf Kotlin/JVM), Faz 1.7'de başlar |
| `data/` | Sözlük/korpus üretim betikleri ve veri kaynakları listesi |
| `docs/` | Plan, kararlar, ölçüm raporları |

## Derleme

`./gradlew assembleDebug`. JDK 21 ve Android SDK'nın yanında **Python 3** gerekir: aksan düzeltici
dizini ve dil modeli derleme sırasında `data/tools/` betikleriyle üretilir.

## Ölçüm aracı hızlı başlangıç

```sh
cd eval
python -m kboard_eval validate testset/*.jsonl
python -m kboard_eval simulate testset/seed-v0.jsonl --wordlist kelimeler.txt -o iz.jsonl
python -m kboard_eval score testset/seed-v0.jsonl -t iz.jsonl --markdown rapor.md
python -m unittest discover -s tests -t .
```

## Lisans

GPL-3.0 (HeliBoard'dan miras). Test seti vakaları kendi `license` alanlarında belirtilen lisanstadır.
