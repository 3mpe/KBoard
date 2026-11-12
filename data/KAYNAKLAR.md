# Veri kaynakları ve lisanslar

Projeye giren her sözlük, korpus, desen listesi ve model burada kaynağı ve lisansıyla listelenir.

| Kaynak | Kullanım | Lisans | Durum |
|---|---|---|---|
| [aosp-dictionaries `main_tr.dict`](https://codeberg.org/Helium314/aosp-dictionaries) | Ana sözlük (HeliBoard'da gömülü) | depodaki lisansa bakılacak | doğrulanacak |
| `data/deasciify-koruma.txt` | Aksansız yazımı da geçerli kelimeler (oto. düzeltme koruması) | CC0-1.0 | elle yazıldı |
| Zemberek-NLP | Ön hesaplama (masaüstü) | Apache-2.0 | doğrulanacak |
| `eval/testset/seed-v0.jsonl` | Test seti | CC0-1.0 | elle yazıldı |

## Türetilen veriler (depoya girmez)

`data/out/` git dışıdır. Yeniden üretmek için:

```sh
python3 -I data/tools/dict_dump.py app/src/main/assets/dicts/main_tr.dict --offensive -o data/out/main_tr.tsv
java -cp "engine/build/install/engine/lib/*" io.github.kboard.engine.deasciify.DeasciifyToolKt \
    build data/out/main_tr.tsv data/deasciify-koruma.txt data/out/deasciify-tr.kbd
```

- `main_tr.tsv`: `main_tr.dict`'ten (v2, sürüm 202, sözlük sürümü 54) çıkarılan 180 841 kelime ve
  0..255 sıklık. Lisansı `main_tr.dict` ile aynıdır (yukarıda "doğrulanacak").
- `deasciify-tr.kbd`: DeasciifySource dizini (biçim: `engine/.../deasciify/DeasciifyIndex.kt`).
