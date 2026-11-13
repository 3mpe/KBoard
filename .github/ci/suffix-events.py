#!/usr/bin/env python3
"""Ek zinciri kaynağının olay düzeyinde etkisi (uyarı; CI'ı kırmaz).

Kullanım: suffix-events.py <testset.jsonl> <ek-yok.jsonl> <ek.jsonl> [--split dev|test|all]

İki izdeki her önek olayında (yazılan boş değil, kelime sonu değil) ilk 3 öneri karşılaştırılır:
değişen olay, hedefin ilk 3'e girdiği (kazanılan) ve çıktığı (kaybedilen) olay sayıları. Kural
(Fable, adım 6): kaybedilen <= kazanılanın yarısı; aşılırsa GitHub uyarısı yazılır.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval"))
from kboard_eval import testset  # noqa: E402
from kboard_eval.turkish import normalize  # noqa: E402


def load(path: Path) -> dict:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        e = json.loads(line)
        if e.get("commit") or not e.get("typed"):
            continue
        out[(e["case_id"], e["word_index"], e["typed"])] = e["suggestions"][:3]
    return out


def compare(cases, a: dict, b: dict) -> dict:
    ids = {c.id: c for c in cases}
    n = changed = gained = lost = 0
    for key, sa in a.items():
        case = ids.get(key[0])
        if case is None or key not in b:
            continue
        sb = b[key]
        n += 1
        if sa != sb:
            changed += 1
        t = normalize(case.target_words[key[1]])
        ha = any(normalize(s) == t for s in sa)
        hb = any(normalize(s) == t for s in sb)
        gained += hb and not ha
        lost += ha and not hb
    return {"events": n, "changed": changed, "gained": gained, "lost": lost}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("testset", type=Path)
    ap.add_argument("base", type=Path)
    ap.add_argument("new", type=Path)
    ap.add_argument("--split", default="all")
    args = ap.parse_args()
    cases = testset.load([args.testset])
    if args.split != "all":
        cases = testset.filter_split(cases, args.split)
    r = compare(cases, load(args.base), load(args.new))
    print(f"önek olayı {r['events']}, ilk 3 değişen {r['changed']} (%{100 * r['changed'] / max(1, r['events']):.1f}), "
          f"hedef kazanılan {r['gained']}, kaybedilen {r['lost']}")
    if r["lost"] * 2 > r["gained"]:
        print(f"::warning::ek zinciri: kaybedilen olay ({r['lost']}) kazanılanın yarısından fazla ({r['gained']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
