# Dil modeli korpusu

| Dosya | Kaynak | Lisans | Cümle |
|---|---|---|---|
| `commonvoice-tr.txt` | Mozilla Common Voice, `server/data/tr/sentence-collector.txt` (main dalı, 2026-11-13'te alındı) | CC0-1.0 | 47 780 |

Satır başına bir cümle, değiştirilmeden alındı. `setimes.txt` alt kümesi kaynağı açıkça CC0
olmadığı için alınmadı. Model `data/tools/build_ngram.py` ile üretilir; test setiyle örtüşen cümleler
derleme sırasında atılır (bkz. `../KAYNAKLAR.md`).
