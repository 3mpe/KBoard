#!/usr/bin/env python3
"""Günlük ifade listesi: bağlamdan sonra beklenen kelime sonraki kelime önerilerinin ilk 3'ünde mi?

Kullanım: ifade.py <liste.tsv> <ad=motor komutu>... [--zorunlu ad]
Her model için isabet sayısını ve satır satır sonucu yazar. --zorunlu verilen modelde "sabit" satırlar
ilk 3'te değilse çıkış 1 (dağıtılan modelin sık kalıpları taşıdığının denetimi). Oran eşik değildir.
"""
import json
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
from kboard_eval.turkish import normalize  # noqa: E402

args = sys.argv[1:]
required = None
if "--zorunlu" in args:
    i = args.index("--zorunlu")
    required = args[i + 1]
    del args[i:i + 2]
rows = []
for line in Path(args[0]).read_text("utf-8").splitlines():
    if not line.strip() or line.startswith("#"):
        continue
    f = line.split("\t")
    rows.append((f[0].split(), [normalize(x) for x in f[1].split("|")], len(f) > 2 and f[2] == "sabit"))
results = {}
for spec in args[1:]:
    name, cmd = spec.split("=", 1)
    p = subprocess.Popen(shlex.split(cmd), stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    out = []
    for ctx, _exp, _pin in rows:
        p.stdin.write(json.dumps({"op": "predict", "context": ctx, "prefix": ""}, ensure_ascii=False) + "\n")
        p.stdin.flush()
        out.append(json.loads(p.stdout.readline())["suggestions"][:3])
    p.stdin.close()
    results[name] = out
names = list(results)
free = [n for n, r in enumerate(rows) if not r[2]]
print("| Model | İlk 3'te (liste) | Sabit satırlar |\n|---|---|---|")
failed = False
for name in names:
    hit = sum(any(normalize(s) in rows[n][1] for s in results[name][n]) for n in free)
    pins = [n for n, r in enumerate(rows) if r[2]]
    pin_hit = sum(any(normalize(s) in rows[n][1] for s in results[name][n]) for n in pins)
    print(f"| {name} | {hit} / {len(free)} | {pin_hit} / {len(pins)} |")
    if name == required and pin_hit != len(pins):
        failed = True
print("\n| Bağlam | Beklenen | " + " | ".join(names) + " |\n|---|---|" + "---|" * len(names))
for n, (ctx, exp, pin) in enumerate(rows):
    cells = []
    for name in names:
        ok = any(normalize(s) in exp for s in results[name][n])
        cells.append(("✓ " if ok else "✗ ") + " · ".join(results[name][n]))
    print(f"| {' '.join(ctx)}{' (sabit)' if pin else ''} | {' / '.join(exp)} | " + " | ".join(cells) + " |")
if failed:
    print(f"\nHATA: {required} modelinde sabit satırların hepsi ilk 3'te değil")
sys.exit(1 if failed else 0)
