# kboard-eval

Klavye önerilerini Türkçe test seti üzerinde puanlayan araç. Format ve metrik tanımları:
[docs/PLAN.md §4](../docs/PLAN.md#4-faz-0-ölçüm-altyapısı-ayrıntılı).

Akış: **test seti** (`testset/*.jsonl`) → **iz** (her tuştan sonra öneriler; masaüstü öngörücü
`simulate` ile ya da cihaz sürücüsüyle üretilir) → **rapor** (`score`).

Öngörücüler:
- `freq`: kelime listesi + önek eşleşmesi. Yalnızca boru hattını sınamak içindir, baseline değildir.
- `subprocess`: JSON-satır protokolüyle harici motor (`engine/` CLI'ı, Faz 1.7+).

Cihaz sürücüsü (`adb`, Gboard ve HeliBoard baseline'ı için) Faz 0.4'te eklenecek.
