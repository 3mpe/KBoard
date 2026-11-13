# Faz 1.8: Faz 1 ölçümü (gerileme kontrolü)

> Durum: **kısmi.** Masaüstü motor ölçümü ve emülatör kontrolü yapıldı. Asıl bitiş kriteri
> (KBoard APK'sını 0.5'teki telefon koşullarında saf HeliBoard ile karşılaştırmak) telefon
> gerektirir: 0.5 baseline'ı (#10, #11) henüz ölçülmedi. #19 açık kalıyor.

## Ne ölçüldü

### 1. Motor, 327 vakalık set (masaüstü, CI'da her PR'da)

Komut: `eval` CI işi "Motor ölçüm eşiği" (`.github/ci/eval-gate.py`). Kaynaklar: aksan düzeltici
(`main_tr.dict`'ten üretilen 180.727 kayıtlık dizin) + kişisel sözlük çekimleri. Sonraki kelime
tahmini ve yazım hatası düzeltme henüz yok (Faz 3–4).

| Metrik | Değer | CI eşiği |
|---|---|---|
| Aksan düzeltme doğruluğu (289 vaka) | %78,2 | ≥ %75 |
| Yanlış otomatik düzeltme (904 kelime) | %0,1 | ≤ %0,3 |
| Kişisel sözlük, ilk-3 @3 | %27,6 | ≥ %25 |
| Genel ilk-3 @3 | %25,9 | – |
| Genel KSR | %18,7 | – |
| Gecikme p95 / en çok | 0,44 ms / 15 ms | – |

Gerileme açısından: motor HeliBoard'un önerilerinin yerine geçmez, üstüne ekler. Motor bir görüş
bildirmediğinde otomatik düzeltme kararı aynen HeliBoard'undur (`KBoardSuggestions.mergeAutoCorrection`, `NoOpinion` dalı). Kişisel sözlük,
kişiler ve kullanıcı geçmişindeki kelimeleri motor hiç düzeltmez.

### 2. Emülatör (CI, API 34, pixel_6)

`screenshots.yml` her PR'da debug APK'yı kurar ve aynı senaryoyu önce imajdaki Gboard'la, sonra
KBoard'la yazar. Görüntüler `ci-screenshots` dalında, commit SHA'sı klasöründe.

- Aksan dizini emülatörde 160–750 ms'de yükleniyor (arka planda; bu sürede klavye HeliBoard gibi
  çalışır).
- "cok" yazarken şerit: **çök · çok · çoklu**; "simdi": **şimdiki · şimdi · şimdiye**.
- Kişisel sözlüğe "Kadıköy" eklenip klavye süreci kapatıldıktan sonra "Kadikoy" yazınca:
  **Kadıköylü · Kadıköy'd · Kadıköy**. Kelimeler süreç başında da motora geliyor.
- İzin kontrolü: APK'da INTERNET izni yok (CI'da zorunlu).

**Bilinen ölçüm sınırı:** emülatörde "cok" + boşluk otomatik düzeltmesi görüntülenemedi.
`adb shell input text` tuşları klavyeye değil doğrudan metin alanına yazıyor (HeliBoard'da donanım
klavyesi varsayılan olarak kapalı). Klavye kelimeyi "geri çağrılmış kelime" (`isResumed`) olarak
görüyor ve HeliBoard'un tasarımı gereği bunları düzeltmiyor. Teşhis logu: motor `Replace(çok)`
diyor, `correctionAllowed=false`. Betik artık ekrandaki tuşlara dokunarak yazıyor, ancak bu koşu
Actions kotası dolduğu için yayınlanamadı.

## Telefonda yapılacaklar (bitiş kriteri)

1. #10/#11: Gboard ve saf HeliBoard baseline'ı (`eval` cihaz sürücüsü, `drive` komutu).
2. Aynı koşullarla KBoard debug APK'sı. Beklenen: dogru_yazim ve sohbet kategorilerinde HeliBoard
   ile aynı ya da daha iyi; yanlış otomatik düzeltme HeliBoard'dan yüksek değil.
3. Elle: "cok" + boşluk → "çok", "sus" + boşluk → "sus" (korunur), kişisel sözlükte "Kadıköy" →
   "Kadikoy'de" + boşluk → "Kadıköy'de".
4. Telefonda aksan dizini yükleme süresi ve tuş başına gecikme (OQ-8 telefon modeli).
