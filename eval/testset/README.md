# Test seti

- `seed-v0.jsonl`: elle yazılmış tohum set (CC0-1.0). Format: `docs/PLAN.md` §4.1.
- `kisisel-v0.jsonl`: kişisel öğrenme seti (Faz 7): 8 sentetik kullanıcı × 40 mesaj, yapay zekâ ile
  üretildi, seed-v0 okunmadan yazıldı (CC0-1.0). `user` alanı bir kullanıcının yazma akışıdır
  (vakaları ardışık olmalı; dev/test ayrımı kullanıcıya göre), `new_words` sözlükte ve dil modeli
  korpusunda olmayan kelimeler. `simulate --online` ile ölçülür; bkz. `docs/reports/faz7-kisisel.md`.

## seed-v0 dağılımı (327 vaka, 1250 kelime)

| Grup | Kimlik öneki | Vaka | Ölçtüğü metrik |
|---|---|---|---|
| Aksansız yazım (`deasciify` etiketi) | `aksan-` (+ 4 `ksoz-`, 7 `yazim-`) | 113 | aksan düzeltme doğruluğu |
| Kişisel sözlük (`user_words` dolu) | `ksoz-` (+ `aksan-0008`) | 54 | ilk-3 / KSR, `kullanici_sozlugu` kırılımı |
| Doğru yazım (`dogru_yazim` kategorisi) | `dogru-` | 102 | yanlış otomatik düzeltme |
| Yazım hatası (`yazim_hatasi` etiketi) | `yazim-` | 57 | yazım hatası düzeltme doğruluğu |
| Genel sohbet / özel isim | `sohbet-`, `ozel-` | 13 | ilk-3, sonraki kelime |

Gruplar örtüşür (aksansız yazılmış kişisel sözlük vakası iki grupta sayılır), toplam 327'yi aşar.

Kelime düzeyinde: 904 doğru yazılmış, 289 yalnızca aksanı eksik, 57 yazım hatalı kelime.

Etiketler: `oto_duzeltme_tuzagi` (yazılan doğru kelimenin aksanlı/şapkalı başka bir kelimesi var:
kas/kaş, on/ön, hala/hâlâ), `belirsiz` (ascii biçim de geçerli kelime), `sik_hata` (sık yanlış
yazılan kelimenin doğru biçimi), `buyuk_I`, `kesme`, `sayi`, `yabanci`, `kismi` (kısmen aksanlı
yazım). Yazım hatası alt türleri: `harf_eksik`, `harf_fazla`, `yer_degistirme`, `komsu_tus`
(Q klavyede komşu tuş), `harf_degisim`, `yaygin_hata` (herkez, yanlız, orjinal gibi).

Not: yalnızca aksan *fazlalığı* olan yazımlar ("şöför" → "şoför") ascii katlamada hedefle aynı
düştüğü için yazım hatası değil, aksan farkı sayılır; bu yüzden yazım hatası vakalarına konmadı.
- Kişisel/gerçek mesajlardan derlenen vakalar buraya **değil**, `eval/private/` altına konur (git dışı).

Yeni vaka eklerken: `input` ile `text` aynı sayıda kelime içermeli, `source` ve `license` doldurulmalı.
Doğrulama: `cd eval && python -m kboard_eval validate testset/*.jsonl`

Not: cümle başındaki belirsiz çiftler (`Su` → `Şu` ile `Su içmeyi unutma`, `On`/`Ön`, `Cam`/`Çam`)
kasıtlı olarak iki yönde de var. Commit boşlukta ve sağ bağlam olmadan yapıldığı için hiçbir klavye
%100 aksan düzeltme ile %0 yanlış otomatik düzeltmeyi birlikte tutturamaz; raporlarda `belirsiz`
kırılımına ayrıca bakılmalı.
