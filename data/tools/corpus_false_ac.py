#!/usr/bin/env python3
"""Korpusta yanlış otomatik düzeltme ölçümü (ayrılmış dilim).

Korpusların her N. satırı (varsayılan 10) ayrılır; dil modeli geri kalanından, CI'daki
`buildNgramModel` ile aynı ayarlarla derlenir. Ayrılan satırlardaki kesmesiz, yalnızca harften
oluşan her sözcük doğru yazılmış sayılır ve motora boş bağlamla, kelimenin tamamı yazılmış olarak
sorulur. Motor boşlukta yazılanı başka bir kelimeyle değiştirirse (Türkçe küçük harfte farklı)
bu bir yanlış otomatik düzeltmedir. Oran, sözcük sıklığıyla (token) ağırlıklıdır.

Kullanım (depo kökünden):
  python3 data/tools/corpus_false_ac.py \\
      --engine engine/build/install/engine/bin/engine \\
      --deasciify engine/build/kboard/deasciify-tr.kbd \\
      --run varsayılan= --run typo-otomatik=--typo-autocorrect --run typo-yok=--no-typo \\
      --gate varsayılan=0.003 --json eval/out/corpus-false-ac.json

Her --run ad=ek-argümanlar bir motor yapılandırmasıdır (ek argümanlar boşlukla ayrılır).
--gate ad=oran: o yapılandırmanın oranı eşiği aşarsa çıkış kodu 1.
--context: her yapılandırma ayrıca "ad+ctx" adıyla, her sözcük satırdaki önceki iki sözcükle
birlikte sorularak da ölçülür (bağlama göre aksan seçimi ve soru eki uyumu yalnız bunda devreye girer).
--compare yeni:taban=fark: "yeni"de olup "taban"da olmayan her yanlış düzeltme tek tek yazılır
(ör. yazım kurallarının katkısı, taban = aynı yapılandırma --no-rules ile). Yazılan sözcüğün kendisi
yanlış yazım listesindeyse ("birşey" → "bir şey": korpus TDK'ya göre yanlış yazılmış, düzeltme
doğru) fark ayrıca işaretlenir. Ham oran farkı her zaman yazılır; eşik, listedeki korpus yanlışları
dışarıda bırakılmış farka uygulanır (karar: docs/reports/faz-kural.md, "Korpus").
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

ROOT = Path(__file__).resolve().parents[2]
MISSPELLINGS = ROOT / "engine/src/main/resources/io/github/kboard/engine/rules/yazim-yanlislari-tr.tsv"
CORPORA = [
    ("cv", ROOT / "data/corpus/commonvoice-tr.txt", 1),
    ("sohbet", ROOT / "data/sohbet/sohbet-v1.txt", 3),
]


def split(every: int, workdir: Path) -> tuple[list[str], list[str]]:
    """Her korpusun her [every]. dolu satırını ayırır; eğitim dosyalarını yazar."""
    held: list[str] = []
    specs: list[str] = []
    for name, path, weight in CORPORA:
        train: list[str] = []
        n = 0
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            n += 1
            (held if n % every == 0 else train).append(line)
        out = workdir / f"{name}-train.txt"
        out.write_text("\n".join(train) + "\n", encoding="utf-8")
        specs.append(f"{name}={out}" + (f":{weight}" if weight != 1 else ""))
    return held, specs


def build_lm(specs: list[str], out: Path) -> None:
    cmd = [sys.executable, str(ROOT / "data/tools/build_ngram.py")]
    for s in specs:
        cmd += ["--corpus", s]
    cmd += [
        "--exclude-jsonl", str(ROOT / "eval/testset/seed-v0.jsonl"),
        "--exclude-jsonl", str(ROOT / "eval/testset/kisisel-v0.jsonl"),
        "--exclude-jsonl", str(ROOT / "eval/testset/kural-v0.jsonl"),
        "--denylist", str(ROOT / "data/ngram-denylist.txt"),
        "--min-uni", "1", "--min-bi", "1", "--min-tri", "2", "--max-succ", "60", "-o", str(out),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)


def held_tokens(lines: list[str]) -> Counter:
    c: Counter = Counter()
    for line in lines:
        for t in b.tokens(line):
            if "'" not in t and t.isalpha():
                c[t] += 1
    return c


def held_in_context(lines: list[str]) -> list[tuple[list[str], str]]:
    """Ayrılan satırlardaki her uygun sözcük, satırdaki önceki (en çok) iki sözcükle."""
    out: list[tuple[list[str], str]] = []
    for line in lines:
        toks = b.tokens(line)
        for i, t in enumerate(toks):
            if "'" not in t and t.isalpha():
                out.append((toks[max(0, i - 2):i], t))
    return out


def run_engine(cmd: list[str], words: list[str], contexts: list[list[str]] | None = None) -> list[str | None]:
    ctxs = contexts or [[] for _ in words]
    req = "".join(json.dumps({"op": "predict", "context": c, "prefix": w}, ensure_ascii=False) + "\n"
                  for c, w in zip(ctxs, words))
    p = subprocess.run(cmd, input=req, capture_output=True, text=True, encoding="utf-8", check=True)
    out = [json.loads(line).get("autocorrect") for line in p.stdout.splitlines() if line.strip()]
    if len(out) != len(words):
        raise SystemExit(f"motor {len(words)} isteğe {len(out)} yanıt verdi")
    return out


def misspelling_matcher():
    """'... yazılan→düzeltme' anahtarındaki yazılan sözcük yanlış yazım listesinde mi (kök kayıtları dahil)?"""
    exact, stems = set(), []
    if MISSPELLINGS.exists():
        for line in MISSPELLINGS.read_text(encoding="utf-8").splitlines():
            line = line.split("#")[0].rstrip()
            if not line.strip():
                continue
            p = line.split("\t")
            exact.add(p[0])
            if len(p) > 2 and p[2].strip() == "kök":
                stems.append(p[0])

    def listed(key: str) -> bool:
        w = b.tr_lower(key.split("→")[0].split(" ")[-1])
        return w in exact or any(w.startswith(s) for s in stems)
    return listed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--engine", required=True, type=Path)
    ap.add_argument("--deasciify", required=True, type=Path)
    ap.add_argument("--every", type=int, default=10, help="her N. satır ayrılır")
    ap.add_argument("--run", action="append", required=True, help="ad=ek motor argümanları")
    ap.add_argument("--gate", action="append", default=[], help="ad=en yüksek oran")
    ap.add_argument("--json", type=Path)
    ap.add_argument("--examples", type=int, default=15)
    ap.add_argument("--context", action="store_true", help="bağlamlı ölçümü de yap (ad+ctx)")
    ap.add_argument("--compare", action="append", default=[], help="yeni:taban=en yüksek oran farkı")
    args = ap.parse_args()
    listed = misspelling_matcher()

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        held, specs = split(args.every, work)
        lm = work / "holdout.kbn"
        build_lm(specs, lm)
        counts = held_tokens(held)
        words = sorted(counts)
        total = sum(counts.values())
        print(f"ayrılan satır {len(held)}, sözcük {total}, ayrı biçim {len(words)}")
        results = {}
        for spec in args.run:
            name, _, extra = spec.partition("=")
            cmd = [str(args.engine), "--deasciify", str(args.deasciify), "--ngram", str(lm)] + shlex.split(extra)
            ac = run_engine(cmd, words)
            bad = [(w, a) for w, a in zip(words, ac) if a is not None and b.tr_lower(a) != b.tr_lower(w)]
            n_bad = sum(counts[w] for w, _ in bad)
            rate = n_bad / total if total else 0.0
            bad.sort(key=lambda x: -counts[x[0]])
            results[name] = {"args": extra, "false_autocorrect": n_bad, "tokens": total, "rate": rate,
                             "examples": [f"{w}→{a} ({counts[w]})" for w, a in bad[: args.examples]],
                             "all": {f"{w}→{a}": counts[w] for w, a in bad}}
            print(f"{name:16s} yanlış oto. düzeltme {n_bad}/{total} = %{rate * 100:.2f}  "
                  + ", ".join(results[name]["examples"][:8]))
            if args.context:
                inst = held_in_context(held)
                ac = run_engine(cmd, [w for _, w in inst], [c for c, _ in inst])
                bad_c: Counter = Counter()
                for (c, w), a in zip(inst, ac):
                    if a is not None and b.tr_lower(a) != b.tr_lower(w):
                        bad_c[f"{' '.join(c)} {w}→{a}".strip()] += 1
                n_bad = sum(bad_c.values())
                rate = n_bad / len(inst) if inst else 0.0
                cname = name + "+ctx"
                results[cname] = {"args": extra, "false_autocorrect": n_bad, "tokens": len(inst), "rate": rate,
                                  "examples": [f"{k} ({v})" for k, v in bad_c.most_common(args.examples)],
                                  "all": dict(bad_c)}
                print(f"{cname:16s} yanlış oto. düzeltme {n_bad}/{len(inst)} = %{rate * 100:.2f}  "
                      + ", ".join(results[cname]["examples"][:8]))
    failed = False
    for spec in args.compare:
        pair, _, limit = spec.partition("=")
        new, _, base = pair.partition(":")
        r_new, r_base = results[new], results[base]
        added = {k: v - r_base["all"].get(k, 0) for k, v in r_new["all"].items() if v > r_base["all"].get(k, 0)}
        removed = sum(v for k, v in r_base["all"].items() if k not in r_new["all"])
        n_list = sum(v for k, v in added.items() if listed(k))
        delta = r_new["rate"] - r_base["rate"]
        delta_unlisted = (sum(added.values()) - n_list - removed) / r_new["tokens"] if r_new["tokens"] else 0.0
        print(f"\n{new} - {base}: ham %{delta * 100:+.3f} puan ({sum(added.values())} yeni, {removed} giden; "
              f"yeni olanlardan {n_list} sözcükte korpusun kendisi listede yanlış yazım); "
              f"onlar hariç %{delta_unlisted * 100:+.3f} puan")
        for k, v in sorted(added.items(), key=lambda kv: (-kv[1], kv[0])):
            print(f"  + {k} ({v}){'  [korpusta yanlış yazım: listede]' if listed(k) else ''}")
        r_new.setdefault("compare", {})[base] = {"delta": delta, "delta_unlisted": delta_unlisted, "added": added,
                                                 "removed": removed, "listed": n_list}
        if limit:
            ok = delta_unlisted <= float(limit)
            print(f"{'OK ' if ok else 'HATA'} korpus yanlış oto. düzeltme farkı ({new} - {base}, listedeki korpus "
                  f"yanlışları hariç): %{delta_unlisted * 100:+.3f} puan (eşik <= %{float(limit) * 100:.3f} puan)")
            failed |= not ok
    for r in results.values():
        r.pop("all", None)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    for g in args.gate:
        name, _, limit = g.partition("=")
        ok = results[name]["rate"] <= float(limit)
        print(f"{'OK ' if ok else 'HATA'} korpus yanlış oto. düzeltme ({name}): "
              f"%{results[name]['rate'] * 100:.2f} (eşik <= %{float(limit) * 100:.1f})")
        failed |= not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
