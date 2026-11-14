# Ağır kayma ölçümü

`agir_probe.py`, harflerin çoğu yandaki tuşa basılarak yazılmış kelimelerde ("kontrol etmek idyrfşö"
→ istedim) motoru ölçer. Gürültü modeli motorun komşu tablosundan bağımsızdır: emülatördeki Türkçe Q
tuşlarının piksel merkezlerine rastgele dokunuş sapması (σ = 0,35 ve 0,5 tuş, üst/alt sıra dahil) ya da
bütün kelimenin aynı yöne kayması. Tohum sabittir; vaka listesinin sha256'sı çıktının ilk satırındadır.
Ayar yalnız `SPLIT=dev` ile yapılır, `SPLIT=test` rapor içindir.

`tuzak-sozluk-disi.txt`: sözlükte olmayan ama doğru yazılmış kelimeler (isim, marka, yabancı, argo).
Bunlarda ağır kayma adayı ne sıklıkla çıkıyor, hangi yuvada, 4. harften sonraki öneklerde ne kadar,
ölçülür.

```
cd eval
B=../engine/build/kboard
SPLIT=test python3 agir/agir_probe.py testset/seed-v0.jsonl agir/tuzak-sozluk-disi.txt \
  ../engine/build/install/engine/bin/engine --deasciify $B/deasciify-tr.kbd --ngram $B/kboard-tr.kbn \
  --denylist ../data/ngram-denylist.txt --suffix $B/suffix-tr.kbs
```
