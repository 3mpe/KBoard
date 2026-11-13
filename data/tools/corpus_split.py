#!/usr/bin/env python3
"""Korpusta ayrık bağlaç önerisinin doğru yazılmış kelimelere çıkması (kural PR B, Fable kararı).

Ayrılan dilim ve dil modeli `corpus_false_ac.py` ile aynıdır (her N. satır ayrılır, model geri
kalanından derlenir). Ayrılan satırlarda -de/-da/-te/-ta/-ki ile biten kesmesiz, yalnız harften
oluşan her sözcük doğru yazılmış sayılır ve motora satırdaki önceki (en çok) iki sözcükle, kelimenin
tamamı yazılmış olarak sorulur. Önerilerde o kelimenin ayrık biçimi ("evde" → "ev de") ilk yuvada
ya da ilk 3'teyse sayılır. Korpusun kendi bitişik yazım yanlışları da ("bende geldim") sayıya
girer; örnekler bu yüzden yazılır.

Kullanım (depo kökünden):
  python3 data/tools/corpus_split.py --engine engine/build/install/engine/bin/engine \\
      --deasciify engine/build/kboard/deasciify-tr.kbd --args "--suffix engine/build/kboard/suffix-tr.kbs" \\
      --gate 0.05 --json eval/out/corpus-split.json

--gate oran: ilk yuvadaki oran eşiği aşarsa çıkış kodu 1.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_ngram as b  # noqa: E402
import corpus_false_ac as cf  # noqa: E402

TAILS = ("de", "da", "te", "ta", "ki")


def candidates(lines: list[str]) -> list[tuple[list[str], str]]:
    out = []
    for line in lines:
        toks = b.tokens(line)
        for i, t in enumerate(toks):
            if "'" not in t and t.isalpha() and len(t) >= 3 and b.tr_lower(t).endswith(TAILS):
                out.append((toks[max(0, i - 2):i], t))
    return out


def is_split_of(s: str, word: str) -> bool:
    p = s.split(" ")
    if len(p) != 2 or p[1].lower() not in ("de", "da", "ki"):
        return False
    return fold(p[0]) == fold(word)[:-2]


_FOLD = str.maketrans("çğıöşüâîû", "cgiosuaiu")


def fold(s: str) -> str:
    return b.tr_lower(s).translate(_FOLD)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--engine", required=True, type=Path)
    ap.add_argument("--deasciify", required=True, type=Path)
    ap.add_argument("--every", type=int, default=10)
    ap.add_argument("--args", default="", help="ek motor argümanları")
    ap.add_argument("--gate", type=float)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--examples", type=int, default=25)
    a = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        held, specs = cf.split(a.every, work)
        lm = work / "holdout.kbn"
        cf.build_lm(specs, lm)
        inst = candidates(held)
        cmd = [str(a.engine), "--deasciify", str(a.deasciify), "--ngram", str(lm)] + shlex.split(a.args)
        req = "".join(json.dumps({"op": "predict", "context": c, "prefix": w}, ensure_ascii=False) + "\n"
                      for c, w in inst)
        p = subprocess.run(cmd, input=req, capture_output=True, text=True, encoding="utf-8", check=True)
        resp = [json.loads(line) for line in p.stdout.splitlines() if line.strip()]
    if len(resp) != len(inst):
        raise SystemExit(f"motor {len(inst)} isteğe {len(resp)} yanıt verdi")
    top1: Counter = Counter()
    top3: Counter = Counter()
    for (c, w), r in zip(inst, resp):
        sug = r.get("suggestions", [])[:3]
        hits = [s for s in sug if is_split_of(s, w)]
        key = f"{' '.join(c)} [{w}]".strip()
        if hits:
            top3[key] += 1
            if sug and sug[0] in hits:
                top1[key] += 1
    n = len(inst)
    r1 = sum(top1.values()) / n if n else 0.0
    r3 = sum(top3.values()) / n if n else 0.0
    print(f"ayrılan satırlarda -de/-da/-te/-ta/-ki ile biten sözcük: {n}")
    print(f"ayrık öneri ilk yuvada: {sum(top1.values())}/{n} = %{r1 * 100:.2f}")
    print(f"ayrık öneri ilk 3'te:   {sum(top3.values())}/{n} = %{r3 * 100:.2f}")
    for k, v in top1.most_common(a.examples):
        print(f"  ilk yuva: {k} ({v})")
    if a.json:
        a.json.parent.mkdir(parents=True, exist_ok=True)
        a.json.write_text(json.dumps({"tokens": n, "top1": sum(top1.values()), "top3": sum(top3.values()),
                                      "top1_rate": r1, "top3_rate": r3, "top1_examples": dict(top1.most_common()),
                                      "top3_examples": dict(top3.most_common(200))},
                                     ensure_ascii=False, indent=1), encoding="utf-8")
    if a.gate is not None and r1 > a.gate:
        print(f"HATA ilk yuva oranı %{r1 * 100:.2f} > eşik %{a.gate * 100:.2f}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
