#!/usr/bin/env python3
"""Yazım kuralları eşiği (kural PR A, Fable kararı): kural-v0 setinin `kboard_eval score --json` çıktısı.

Kullanım: kural-gate.py <kural.json> [--trace iz.jsonl --testset kural-v0.jsonl]

- Tuzak vakalarda (kural_tuzak) otomatik düzeltme: 0 (pazarlıksız)
- Kural setinde otomatik düzeltme kesinliği >= %99
- Yanlış yazım listesi duyarlılığı (kural_liste, düzeltilen) >= %95
- Bitişik uzun soru eki duyarlılığı (kural_soru) >= %85
--trace verilirse tuzaklardaki ve kesinliği bozan düzeltmeler tek tek yazılır.
"""
import argparse
import json
import sys
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("score", type=Path)
ap.add_argument("--trace", type=Path)
ap.add_argument("--testset", type=Path)
args = ap.parse_args()

d = json.load(open(args.score, encoding="utf-8"))
t = d["by_tag"]


def ratio(tag, key):
    r = t.get(tag, {}).get(key, {})
    return r.get("value"), r.get("hit", 0), r.get("total", 0)


trap_v, trap_hit, trap_n = ratio("kural_tuzak", "false_autocorrect_rate")
prec_v, prec_hit, prec_n = ratio("kural", "autocorrect_precision")
list_v, list_hit, list_n = ratio("kural_liste", "fix_recall")
q_v, q_hit, q_n = ratio("kural_soru", "fix_recall")
checks = [
    ("tuzaklarda oto. düzeltme (kelime)", trap_hit, "==", 0, f"{trap_hit}/{trap_n}"),
    ("kural seti oto. düzeltme kesinliği", prec_v if prec_n else 1.0, ">=", 0.99, f"{prec_hit}/{prec_n}"),
    ("yanlış yazım listesi duyarlılığı", list_v, ">=", 0.95, f"{list_hit}/{list_n}"),
    ("bitişik soru eki duyarlılığı", q_v, ">=", 0.85, f"{q_hit}/{q_n}"),
]
failed = False
for name, value, op, limit, frac in checks:
    if value is None:
        print(f"HATA {name}: ölçülemedi")
        failed = True
        continue
    ok = value == limit if op == "==" else value >= limit
    shown = f"{value}" if op == "==" else f"%{value * 100:.1f}"
    lim = f"{limit}" if op == "==" else f"%{limit * 100:.1f}"
    print(f"{'OK ' if ok else 'HATA'} {name}: {shown} ({frac}; eşik {op} {lim})")
    failed |= not ok

if args.trace and args.testset:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval"))
    from kboard_eval import testset, trace  # noqa: E402
    from kboard_eval.turkish import same_word  # noqa: E402

    cases = {c.id: c for c in testset.load(args.testset)}
    for ev in trace.read(args.trace):
        if not ev.commit or ev.case_id not in cases:
            continue
        c = cases[ev.case_id]
        typed, target = c.typed_words[ev.word_index], c.aligned_targets[ev.word_index]
        committed = ev.autocorrect if ev.autocorrect is not None else typed
        if not same_word(committed, typed) and not same_word(committed, target):
            print(f"  yanlış düzeltme {c.id}: {typed} → {committed} (hedef {target})")
        elif same_word(committed, typed) and not same_word(typed, target) and "kural_dedaki" not in c.tags:
            print(f"  kaçan {c.id}: {typed} (hedef {target})")
sys.exit(1 if failed else 0)
