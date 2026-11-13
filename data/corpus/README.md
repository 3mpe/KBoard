# Dil modeli korpusu

| Dosya | Kaynak | Lisans | Satır |
|---|---|---|---|
| `commonvoice-tr.txt` | Mozilla Common Voice, `server/data/tr/sentence-collector.txt`, `main` dalı, 2026-11-13'te alındı | CC0-1.0 | 47 781 |

- sha256: `28e2d0955f640d001dd90c6205c1e55cde18528a2bed45cc99389bc0d4058ece` (alındığı gün `main`
  dalındaki dosyayla aynı). Upstream commit SHA'sı bu ortamdan okunamadı; dosya değişmeden alındı,
  satır başına bir cümle.
- İçerik: gönüllülerin topladığı cümleler; bir kısmı 1950 öncesi edebi metinlerden (eski yazımlar,
  roman karakter adları, OCR hataları). Öneri olarak çıkmaması gerekenler `../ngram-denylist.txt`.
- `setimes.txt` alt kümesi kaynağı açıkça CC0 olmadığı için alınmadı.

Model `data/tools/build_ngram.py` ile üretilir; test setiyle örtüşen cümleler derleme sırasında
atılır (bkz. `../KAYNAKLAR.md`).
