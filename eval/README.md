# kboard-eval

Klavye önerilerini Türkçe test seti üzerinde puanlayan araç. Format ve metrik tanımları:
[docs/PLAN.md §4](../docs/PLAN.md#4-faz-0-ölçüm-altyapısı-ayrıntılı).

Akış: **test seti** (`testset/*.jsonl`) → **iz** (her tuştan sonra öneriler; masaüstü öngörücü
`simulate` ile ya da cihaz sürücüsüyle üretilir) → **rapor** (`score`).

Öngörücüler:
- `freq`: kelime listesi + önek eşleşmesi. Yalnızca boru hattını sınamak içindir, baseline değildir.
- `subprocess`: JSON-satır protokolüyle harici motor (`engine/` CLI'ı, Faz 1.7+).

Cihaz sürücüsü (`drive`, Gboard ve HeliBoard baseline'ı için): vakaları telefonda `adb shell input tap`
ile tuşlara dokunarak yazar, öneri çubuğunu `uiautomator dump` ile okur. Kurulum, önkoşullar ve tuş
haritası biçimi: [keymaps/README.md](keymaps/README.md).

```sh
python -m kboard_eval drive testset/seed-v0.jsonl -k keymaps/<harita>.json -o out/heliboard.jsonl
python -m kboard_eval drive testset/seed-v0.jsonl -k keymaps/<harita>.json -o out/t.jsonl --limit 5 --runs 3
```

`--runs 3`, aynı vakaları üç kez yazar ve izler farklıysa farkları basıp 1 ile çıkar (Faz 0.4 bitiş
kriteri).
