#!/usr/bin/env python3
"""de/da/ki ayrımı eşiği (kural PR B, Fable kararı): dedaki-v0 izinden (`kboard_eval simulate`).

Kullanım: dedaki-gate.py <iz.jsonl> <dedaki-v0.jsonl> [--split test] [--json çıktı.json]

Kelimenin tamamı yazıldığında (boşluktan önceki öneriler, ilk 3):
- Bitişik yazılmış bağlaçta (kural_deda, kural_ki) ayrık hedef ilk 3'te >= %60, ilk yuvada >= %40
- Tuzak vakalarda (kural_tuzak, doğru cümle) bir kelimenin ayrık biçimi ilk yuvada: vakaların <= %10
- Hiçbir kelimede ayrım otomatik düzeltme olmaz (yalnız öneri); yanlış yazım listesinin (kural PR A)
  kendi düzeltmeleri ("yinede" → "yine de") hariç
Tuzaklarda ilk 3'te ayrım oranı ayrıca yazılır (eşiksiz; Fable ~%40 üstüne itiraz eder).
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
from kboard_eval import testset, trace  # noqa: E402
from kboard_eval.turkish import same_word  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("trace", type=Path)
ap.add_argument("testset", type=Path)
ap.add_argument("--split", choices=["dev", "test", "all"], default="test")
ap.add_argument("--json", type=Path)
args = ap.parse_args()

cases = testset.load(args.testset)
if args.split != "all":
    cases = testset.filter_split(cases, args.split)
cases = {c.id: c for c in cases}

LIST = Path(__file__).resolve().parents[2] / "engine/src/main/resources/io/github/kboard/engine/rules/yazim-yanlislari-tr.tsv"
listed = set()
for line in LIST.read_text(encoding="utf-8").splitlines():
    line = line.split("#")[0].rstrip()
    if line.strip():
        listed.add(line.split("\t")[0].strip().lower())

FOLD = str.maketrans("çğıöşüâîûIİ", "cgiosuaiuii")


def fold(s: str) -> str:
    return s.translate(FOLD).lower()


def is_split_of(s: str, typed: str) -> bool:
    p = s.split(" ")
    return len(p) == 2 and fold(p[1]) in ("de", "da", "ki") and fold(p[0]) == fold(typed)[:-2]


err = defaultdict(lambda: [0, 0, 0])  # etiket -> [n, ilk yuva, ilk 3]
trap = defaultdict(lambda: [False, False])  # vaka -> [ilk yuvada ayrım, ilk 3'te ayrım]
split_ac = []
misses = []
for ev in trace.read(args.trace):
    if not ev.commit or ev.case_id not in cases:
        continue
    c = cases[ev.case_id]
    typed, target = c.typed_words[ev.word_index], c.aligned_targets[ev.word_index]
    sug = ev.suggestions[:3]
    if ev.autocorrect and is_split_of(ev.autocorrect, typed) and fold(typed) not in {fold(w) for w in listed}:
        split_ac.append(f"{c.id}: {typed} → {ev.autocorrect}")
    if "kural_tuzak" in c.tags:
        hits = [s for s in sug if is_split_of(s, typed)]
        trap[c.id][0] |= bool(sug) and sug[0] in hits
        trap[c.id][1] |= bool(hits)
    elif " " in target and fold(target.replace(" ", "")) == fold(typed):
        kind = "kural_ki" if "kural_ki" in c.tags else "kural_deda"
        t1 = bool(sug) and same_word(sug[0], target)
        t3 = any(same_word(s, target) for s in sug)
        for k in (kind, "hepsi"):
            err[k][0] += 1
            err[k][1] += t1
            err[k][2] += t3
        if not t3:
            misses.append(f"{c.id}: {typed} → {target} (öneriler: {', '.join(sug)})")


def pct(a, n):
    return a / n if n else None


out = {"split": args.split}
for k, (n, a, b) in sorted(err.items()):
    out[k] = {"n": n, "top1": pct(a, n), "top3": pct(b, n)}
n_trap = len(trap)
out["tuzak"] = {"n": n_trap, "top1": pct(sum(a for a, _ in trap.values()), n_trap),
                "top3": pct(sum(b for _, b in trap.values()), n_trap)}
out["split_autocorrect"] = split_ac

h = out.get("hepsi", {"n": 0, "top1": None, "top3": None})
checks = [
    ("bitişik bağlaç: ayrım ilk 3'te", h["top3"], ">=", 0.60, h["n"]),
    ("bitişik bağlaç: ayrım ilk yuvada", h["top1"], ">=", 0.40, h["n"]),
    ("tuzak: ayrım ilk yuvada (vaka)", out["tuzak"]["top1"], "<=", 0.10, n_trap),
    ("ayrım otomatik düzeltme oldu", len(split_ac), "==", 0, None),
]
failed = False
print(f"de/da/ki ayrımı ({args.split} yarısı)")
for k in ("kural_deda", "kural_ki"):
    if k in out:
        print(f"  {k}: n={out[k]['n']} ilk yuva %{out[k]['top1'] * 100:.1f}, ilk 3 %{out[k]['top3'] * 100:.1f}")
print(f"  tuzak: n={n_trap}, ilk 3'te ayrım %{(out['tuzak']['top3'] or 0) * 100:.1f} (eşiksiz)")
for name, v, op, lim, n in checks:
    if v is None:
        print(f"HATA {name}: ölçülemedi")
        failed = True
        continue
    ok = v >= lim if op == ">=" else v <= lim if op == "<=" else v == lim
    shown = f"%{v * 100:.1f}" if op != "==" else str(v)
    lim_s = f"%{lim * 100:.1f}" if op != "==" else str(lim)
    print(f"{'OK ' if ok else 'HATA'} {name}: {shown}{f' (n={n})' if n is not None else ''}; eşik {op} {lim_s}")
    failed |= not ok
for s in split_ac:
    print(f"  otomatik düzeltme: {s}")
for m in misses:
    print(f"  ilk 3'te yok: {m}")
if args.json:
    args.json.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
sys.exit(1 if failed else 0)
