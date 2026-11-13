#!/usr/bin/env python3
"""data/sohbet/ korpusu için basit denetim (yalnızca standart kitaplık).

Kullanım: python3 -I data/tools/sohbet_check.py [dosya] [--top N] [--max-prefix K]

Satır, kelime, ortanca uzunluk, tam tekrar sayısı ve en sık ilk 4 kelimelik
başlangıçları yazdırır. Tam tekrar varsa ya da bir başlangıç K'den (varsayılan 2)
fazla satırda geçiyorsa çıkış kodu 1 olur.
"""
import argparse
import re
import statistics
import sys
import unicodedata
from collections import Counter

PUNCT = re.compile(r"[^\w'’]+", re.UNICODE)


def tr_lower(s: str) -> str:
    return s.replace("I", "ı").replace("İ", "i").lower()


def words(line: str) -> list[str]:
    return [w for w in PUNCT.split(tr_lower(line)) if w]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?", default="data/sohbet/sohbet-v1.txt")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--max-prefix", type=int, default=2)
    ap.add_argument("--min-words", type=int, default=2)
    ap.add_argument("--max-words", type=int, default=20)
    a = ap.parse_args()

    with open(a.path, encoding="utf-8") as f:
        raw = f.read()
    lines = raw.split("\n")
    if lines and lines[-1] == "":
        lines.pop()

    problems = 0
    empty = [i + 1 for i, l in enumerate(lines) if not l.strip()]
    nfc = [i + 1 for i, l in enumerate(lines) if unicodedata.normalize("NFC", l) != l]
    ws = [i + 1 for i, l in enumerate(lines) if l != l.strip() or "  " in l]

    toks = [words(l) for l in lines]
    lens = [len(t) for t in toks]
    out_of_range = [(i + 1, n) for i, n in enumerate(lens) if not a.min_words <= n <= a.max_words]

    norm = Counter(" ".join(t) for t in toks)
    dups = {k: v for k, v in norm.items() if v > 1}
    exact = Counter(lines)
    exact_dups = sum(v - 1 for v in exact.values() if v > 1)

    prefixes = Counter(" ".join(t[:4]) for t in toks if len(t) >= 4)
    over = [(p, c) for p, c in prefixes.items() if c > a.max_prefix]

    total_words = sum(lens)
    print(f"satır: {len(lines)}")
    print(f"kelime: {total_words}")
    if lens:
        print(f"ortanca uzunluk: {statistics.median(lens)}  (ort. {total_words / len(lens):.2f}, en az {min(lens)}, en çok {max(lens)})")
    print(f"farklı kelime: {len(set(w for t in toks for w in t))}")
    print(f"tam tekrar: {exact_dups}")
    print(f"normalize tekrar (küçük harf, noktalamasız): {sum(v - 1 for v in dups.values())}")
    print(f"boş satır: {len(empty)}, NFC dışı: {len(nfc)}, fazla boşluk: {len(ws)}")
    print(f"uzunluk aralığı dışı ({a.min_words}-{a.max_words}): {len(out_of_range)}")
    print(f"en sık ilk 4 kelime (sınır {a.max_prefix}):")
    for p, c in prefixes.most_common(a.top):
        print(f"  {c:3d}  {p}")

    for k, v in sorted(dups.items(), key=lambda x: -x[1])[:20]:
        print(f"TEKRAR x{v}: {k}")
    for p, c in over:
        print(f"SINIR AŞIMI x{c}: {p}")
    for i, n in out_of_range[:20]:
        print(f"UZUNLUK satır {i}: {n} kelime")
    for i in (empty + nfc + ws)[:20]:
        print(f"BİÇİM satır {i}")

    problems = exact_dups + len(dups) + len(over) + len(out_of_range) + len(empty) + len(nfc) + len(ws)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
