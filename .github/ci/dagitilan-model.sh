#!/usr/bin/env bash
# Dağıtılan dil modelinin (kboard-tr-ship.kbn, süzgeçsiz) güvenlik denetimleri (ci.yml, "Motor ölçüm eşiği").
# Kalite sayıları (İlk-3, KSR, aksan) yalnız ölçüm modelinden (kboard-tr.kbn) raporlanır: dağıtılan model
# test cümlelerini gördüğü için o sayılar burada kalite ölçüsü DEĞİLDİR ve eşiğe girmez.
# Denetlenen: seed-v0 yanlış otomatik düzeltme, kural-v0 eşikleri (tuzakta 0 düzeltme), dedaki-v0 eşikleri
# (ayrım otomatik düzeltme olmaz), gecikme p95, model boyutu, günlük ifade listesinin sabit satırları.
set -euo pipefail
B=engine/build
ship=$B/kboard/kboard-tr-ship.kbn
evalm=$B/kboard/kboard-tr.kbn
[ -s "$ship" ] && [ -s "$evalm" ] || { echo "::error::model dosyaları eksik"; exit 1; }
if cmp -s "$ship" "$evalm"; then echo "::error::dağıtılan model ile ölçüm modeli aynı dosya"; exit 1; fi
size=$(stat -c %s "$ship")
echo "kboard-tr-ship.kbn: $size bayt (ölçüm modeli: $(stat -c %s "$evalm") bayt)"
[ "$size" -le 2000000 ] || { echo "::error::kboard-tr-ship.kbn $size bayt, bütçe 2 000 000"; exit 1; }
base="../$B/install/engine/bin/engine --deasciify ../$B/kboard/deasciify-tr.kbd --denylist ../data/ngram-denylist.txt --suffix ../$B/kboard/suffix-tr.kbs --ngram"
cd eval
mkdir -p out
python -m kboard_eval simulate testset/seed-v0.jsonl -o out/ship-seed.jsonl --predictor subprocess --command "$base ../$ship"
python -m kboard_eval score testset/seed-v0.jsonl -t out/ship-seed.jsonl --json out/ship-seed.json >/dev/null
python - <<'PY'
import json, sys
r = json.load(open("out/ship-seed.json", encoding="utf-8"))
fa = r["overall"]["false_autocorrect_rate"]
p95 = (r.get("latency_ms") or {}).get("p95")
ok = fa["value"] <= 0.003
print(f"{'OK ' if ok else 'HATA'} dağıtılan model, seed-v0 yanlış oto. düzeltme: %{fa['value'] * 100:.2f} ({fa['hit']}/{fa['total']}; eşik <= %0.3)")
lat_ok = p95 is not None and p95 <= 5.0
print(f"{'OK ' if lat_ok else 'HATA'} dağıtılan model, gecikme p95: {p95} ms (eşik <= 5 ms)")
sys.exit(0 if ok and lat_ok else 1)
PY
python -m kboard_eval simulate testset/kural-v0.jsonl -o out/ship-kural.jsonl --predictor subprocess --command "$base ../$ship"
python -m kboard_eval score testset/kural-v0.jsonl -t out/ship-kural.jsonl --json out/ship-kural.json >/dev/null
python ../.github/ci/kural-gate.py out/ship-kural.json --trace out/ship-kural.jsonl --testset testset/kural-v0.jsonl
python -m kboard_eval simulate testset/dedaki-v0.jsonl -o out/ship-dedaki.jsonl --predictor subprocess --command "$base ../$ship"
python ../.github/ci/dedaki-gate.py out/ship-dedaki.jsonl testset/dedaki-v0.jsonl --split test --json out/ship-dedaki.json
echo
echo "### Günlük ifade listesi (sonraki kelime ilk 3; eşik değil, sabit satırlar dağıtılan modelde zorunlu)"
python ../.github/ci/ifade.py ifade/gunluk-ifadeler-v0.tsv "ölçüm modeli=$base ../$evalm" "dağıtılan model=$base ../$ship" --zorunlu "dağıtılan model"
