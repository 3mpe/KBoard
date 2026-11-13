#!/usr/bin/env python3
"""Ek zinciri tamamlamasının korpus ölçümü (plan adım 6; ayrılmış dilim).

corpus_false_ac.py ile aynı bölme: korpusların her N. dolu satırı (varsayılan 10) ayrılır, dil
modeli geri kalanından CI ayarlarıyla derlenir. Ayrılan satırlarda hem sözlükte (main_tr.tsv) hem
eğitim dil modelinde olmayan, ama bilinen bir kökle başlayan sözcükler hedeftir. "Bilinen kök":
sözcüğün en az 3 harfli, sözcükten kısa bir öneki kök tablosunda (data/suffix/stems-tr.tsv) var;
yumuşamış kök (kitab → kitap, Y özniteliği) ve fiilde mastar (anlaş → anlaşmak) da sayılır.
Kesmeli sözcükte (Ankara'dan) kök kesmeden öncesidir ve sözlükte olması yeter.

Her hedef satırdaki önceki iki sözcükle, harf harf yazılarak sorulur. Ölçüler:
- erişim: son harften önce (k < uzunluk) ilk 3 öneride çıkan hedef oranı;
- KSR: eval/kboard_eval ile aynı tanım (tam = uzunluk + 1, kullanılan = ilk isabet + 1).

Ek zinciri tablosu da ayrılmış dilim dışarıda bırakılarak derlenmeli (build_suffix_chains.py
--holdout-every 10; sonra SuffixTool build); motor yapılandırmaları --run ad=ek-argümanlar.

Kullanım (depo kökünden):
  python3 data/tools/suffix_corpus_eval.py --engine engine/build/install/engine/bin/engine \\
      --deasciify engine/build/kboard/deasciify-tr.kbd --dict engine/build/kboard/main_tr.tsv \\
      --run ek-yok= --run ek="--suffix /yol/suffix-holdout.kbs" --json eval/out/suffix-corpus.json
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
import corpus_false_ac as cfa  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
HARDEN = {"b": "p", "d": "t", "g": "k", "ğ": "k", "c": "ç"}


def varint(buf: bytes, p: int) -> tuple[int, int]:
    n = s = 0
    while True:
        x = buf[p]
        p += 1
        n |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return n, p


def lm_vocab(path: Path) -> set[str]:
    """.kbn sözcük tablosu (NgramModel): başlık 8 bayt, vint V, her sözcük vint uzunluk + UTF-8 +
    u8 bayrak + u8; bayrak 2 ise ek dize. Bayrak 1 (yalnız bağlam) olanlar sözcük sayılmaz."""
    buf = path.read_bytes()
    p = 8
    n, p = varint(buf, p)
    out: set[str] = set()
    for _ in range(n):
        ln, p = varint(buf, p)
        w = buf[p:p + ln].decode("utf-8")
        p += ln
        fl = buf[p]
        p += 2
        if fl & 2:
            ln, p = varint(buf, p)
            p += ln
        if not fl & 1:
            out.add(w)
    return out


def load_stems(path: Path) -> dict[str, tuple[str, str]]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        w, cls, attrs = line.split("\t")
        out[w] = (cls, attrs)
    return out


def known_stem(word: str, stems: dict[str, tuple[str, str]], dic: set[str]) -> bool:
    if "'" in word:
        root = word.split("'", 1)[0]
        return len(root) >= 3 and root in dic
    for j in range(3, len(word)):
        sub = word[:j]
        if sub in stems:
            return True
        if any(stems.get(sub + inf, ("", ""))[0] in ("V", "NV") for inf in ("mak", "mek")):
            return True
        hard = HARDEN.get(sub[-1])
        if hard and "Y" in stems.get(sub[:-1] + hard, ("", ""))[1]:
            return True
    return False


def targets(held: list[str], dic: set[str], lm: set[str], stems) -> list[tuple[list[str], str]]:
    out = []
    for line in held:
        toks = b.tokens(line)
        for i, t in enumerate(toks):
            if not t.replace("'", "").isalpha() or t.count("'") > 1 or t.startswith("'") or t.endswith("'"):
                continue
            low = b.tr_lower(t)
            if low in dic or low in lm or not known_stem(low, stems, dic):
                continue
            out.append((toks[max(0, i - 2):i], t))
    return out


def run(cmd: list[str], inst: list[tuple[list[str], str]]) -> list[int | None]:
    """Her hedef için ilk 3'te çıktığı ilk önek uzunluğu (1..uzunluk-1) ya da None."""
    reqs = []
    for ctx, t in inst:
        for k in range(1, len(t)):
            reqs.append(json.dumps({"op": "predict", "context": ctx, "prefix": t[:k]}, ensure_ascii=False))
    p = subprocess.run(cmd, input="\n".join(reqs) + "\n", capture_output=True, text=True, encoding="utf-8", check=True)
    lines = [json.loads(x) for x in p.stdout.splitlines() if x.strip()]
    if len(lines) != len(reqs):
        raise SystemExit(f"motor {len(reqs)} isteğe {len(lines)} yanıt verdi")
    out: list[int | None] = []
    pos = 0
    for _, t in inst:
        tl = b.tr_lower(t)
        first = None
        for k in range(1, len(t)):
            if first is None and any(b.tr_lower(s) == tl for s in lines[pos]["suggestions"][:3]):
                first = k
            pos += 1
        out.append(first)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--engine", required=True, type=Path)
    ap.add_argument("--deasciify", required=True, type=Path)
    ap.add_argument("--dict", required=True, type=Path, help="main_tr.tsv (dict_dump.py)")
    ap.add_argument("--stems", type=Path, default=ROOT / "data/suffix/stems-tr.tsv")
    ap.add_argument("--every", type=int, default=10)
    ap.add_argument("--run", action="append", required=True, help="ad=ek motor argümanları")
    ap.add_argument("--json", type=Path)
    ap.add_argument("--examples", type=int, default=20)
    args = ap.parse_args()

    dic = {b.tr_lower(line.split("\t")[0]) for line in args.dict.read_text(encoding="utf-8").splitlines() if line}
    stems = load_stems(args.stems)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        held, specs = cfa.split(args.every, work)
        lm_path = work / "holdout.kbn"
        cfa.build_lm(specs, lm_path)
        inst = targets(held, dic, lm_vocab(lm_path), stems)
        types = Counter(b.tr_lower(t) for _, t in inst)
        print(f"ayrılan satır {len(held)}, hedef {len(inst)} (ayrı biçim {len(types)})")
        results = {}
        for spec in args.run:
            name, _, extra = spec.partition("=")
            cmd = [str(args.engine), "--deasciify", str(args.deasciify), "--ngram", str(lm_path)] + shlex.split(extra)
            first = run(cmd, inst)
            reached = sum(f is not None for f in first)
            full = sum(len(t) + 1 for _, t in inst)
            used = sum(len(t) + 1 if f is None else f + 1 for (_, t), f in zip(inst, first))
            ex = Counter(f"{t}@{f}" for (_, t), f in zip(inst, first) if f is not None)
            miss = Counter(b.tr_lower(t) for (_, t), f in zip(inst, first) if f is None)
            results[name] = {
                "args": extra, "targets": len(inst), "reached": reached,
                "reach": reached / len(inst) if inst else 0.0,
                "ksr": 1 - used / full if full else 0.0,
                "reached_examples": [k for k, _ in ex.most_common(args.examples)],
                "missed_examples": [k for k, _ in miss.most_common(args.examples)],
            }
            r = results[name]
            print(f"{name:12s} erişim {reached}/{len(inst)} = %{r['reach'] * 100:.1f}  KSR %{r['ksr'] * 100:.1f}")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
