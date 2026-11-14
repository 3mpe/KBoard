#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Türkçe n-gram dil modeli (KBoard sonraki kelime + bağlama göre sıralama) üretir.

Girdi: satır başına bir cümle/mesaj olan UTF-8 metin dosyaları (`--corpus ad=yol[:ağırlık]`).
Çıktı: motorun okuduğu ikili dosya (`.kbn`, biçim aşağıda) ve bir özet.

Model: kelime bigram'ı + (sayısı >= --min-tri olan) trigram'lar, araya katmalı Kneser-Ney
(D = 0.75). Kelimeler Türkçe küçük harfle tutulur; her kelimenin gösterim biçimi, cümle başı
dışındaki en sık yazılışıdır ("ankara" → "Ankara").

Test sızıntısı: `--exclude-jsonl` ile verilen test setindeki herhangi bir vakayla normalize
edilmiş 4 kelimelik ortak dizisi olan eğitim cümlesi atılır; 4 kelimeden kısa vakalar için
vakanın tamamını içeren cümleler atılır.

Öneri yasağı: `--denylist` dosyasındaki kelimeler (küfür, OCR hatası; `önek*` biçimi desteklenir)
modelde bağlam olarak kalır ama hiçbir zaman aday olarak önerilmez (bayrak 1).

Biçim 3 (vint = işaretsiz LEB128; q = u8 nicemlenmiş log10 olasılık, log10 P = -q · 8 / 255):
  "KBNG" u32be sürüm(3)
  vint V; V kez: vint+utf8 kelime (küçük harf, sıralı) u8 bayrak (1 = önerme, 2 = gösterim farklı,
                 4 = cümle ortasında hiç görülmedi: gösterim biçimi kanıtsız, küçük harf varsayılan)
                 q P_uni [bayrak 2 ise: vint+utf8 gösterim]
  vint bos_id                      (cümle başı belirteci "<s>")
  vint topUniN; topUniN kez vint id (P_uni'ye göre azalan; bağlam bilinmezken öneri)
  vint B; B kez (bağlam id'sine göre artan): vint bağlam_id farkı, q gamma, vint n, n kez: vint id q P
  vint T; T kez ((id1, id2)'ye göre artan): vint id1 vint id2 q gamma vint n, n kez: vint id q P
  Satır içi kayıtlar P'ye göre azalan.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path

_UPPER_TO_LOWER = str.maketrans({"I": "ı", "İ": "i"})
_WORD_RE = re.compile(r"[^\W_\d]+(?:['’][^\W_]+)*", re.UNICODE)
BOS = "<s>"
D = 0.75
QMAX = 8.0  # nicemleme: log10 olasılık [-QMAX, 0] → 0..255


def varint(n: int) -> bytes:
    """İşaretsiz LEB128."""
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tr_lower(s: str) -> str:
    return s.translate(_UPPER_TO_LOWER).lower().replace("’", "'")


def tokens(line: str) -> list[str]:
    return [t.replace("’", "'") for t in _WORD_RE.findall(line)]


def norm_tokens(line: str) -> list[str]:
    return [tr_lower(t) for t in tokens(line)]


def load_exclusions(paths: list[Path]) -> tuple[set[tuple[str, ...]], list[str]]:
    grams: set[tuple[str, ...]] = set()
    short: list[str] = []
    for p in paths:
        for line in p.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("//"):  # testset.py gibi: yorum satırı
                continue
            obj = json.loads(line)
            for field in ("text", "input"):
                if not obj.get(field):
                    continue
                toks = norm_tokens(obj[field])
                if len(toks) >= 4:
                    grams.update(tuple(toks[i:i + 4]) for i in range(len(toks) - 3))
                elif toks:
                    short.append(" " + " ".join(toks) + " ")
    return grams, short


def leaks(toks: list[str], grams, short) -> bool:
    if any(tuple(toks[i:i + 4]) in grams for i in range(len(toks) - 3)):
        return True
    if short:
        joined = " " + " ".join(toks) + " "
        return any(s in joined for s in short)
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--corpus", action="append", required=True, help="ad=yol[:ağırlık] (ağırlık: tamsayı tekrar)")
    ap.add_argument("--exclude-jsonl", action="append", default=[], type=Path)
    ap.add_argument("--min-uni", type=int, default=2, help="sözlüğe girmek için en az sayı")
    ap.add_argument("--min-bi", type=int, default=2)
    ap.add_argument("--min-tri", type=int, default=3)
    ap.add_argument("--max-succ", type=int, default=40, help="bağlam başına en çok ardıl")
    ap.add_argument("--top-uni", type=int, default=50)
    ap.add_argument("--denylist", type=Path, help="önerilmeyecek kelimeler (satır başına; önek* desteklenir)")
    ap.add_argument("--holdout", type=Path, help="her 20. cümleyi bu dosyaya ayır (ayar için)")
    ap.add_argument("-o", "--output", type=Path, required=True)
    args = ap.parse_args()

    grams, short = load_exclusions(args.exclude_jsonl)
    sents: list[list[str]] = []
    surface: dict[str, Counter] = defaultdict(Counter)
    dropped = Counter()
    kept = Counter()
    holdout = []
    for spec in args.corpus:
        name, rest = spec.split("=", 1)
        path, _, w = rest.partition(":")
        weight = int(w) if w else 1
        n = 0
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            raw = tokens(line)
            if not raw:
                continue
            toks = [tr_lower(t) for t in raw]
            if leaks(toks, grams, short):
                dropped[name] += 1
                continue
            n += 1
            if args.holdout and n % 20 == 0:
                holdout.append(line)
                continue
            kept[name] += 1
            for i, (r, t) in enumerate(zip(raw, toks)):
                if i > 0:
                    surface[t][r] += weight
            for _ in range(weight):
                sents.append(toks)
    if args.holdout:
        args.holdout.write_text("\n".join(holdout) + "\n", encoding="utf-8")

    uni = Counter(t for s in sents for t in s)
    vocab = {t for t, c in uni.items() if c >= args.min_uni}
    UNK = None

    def m(t):
        return t if t in vocab else UNK

    bi = Counter()
    tri = Counter()
    for s in sents:
        seq = [BOS] + [m(t) for t in s]
        for i in range(1, len(seq)):
            if seq[i] is None:
                continue
            if seq[i - 1] is not None:
                bi[(seq[i - 1], seq[i])] += 1
            if i >= 2 and seq[i - 1] is not None and seq[i - 2] is not None:
                tri[(seq[i - 2], seq[i - 1], seq[i])] += 1

    # Kneser-Ney: tek kelime olasılığı = devam sayısı (kaç farklı kelimeden sonra geldi).
    cont = Counter(w for (_, w) in bi)
    total_cont = sum(cont.values())
    V = len(vocab)
    p_uni = {w: 0.9 * cont[w] / total_cont + 0.1 / V for w in vocab}

    ctx_count = Counter()
    ctx_types = Counter()
    for (v, w), c in bi.items():
        ctx_count[v] += c
        ctx_types[v] += 1

    def p_bi_full(v, w):
        cv = ctx_count.get(v, 0)
        if not cv:
            return p_uni[w]
        c = bi.get((v, w), 0)
        return max(c - D, 0) / cv + D * ctx_types[v] / cv * p_uni[w]

    succ = defaultdict(list)
    for (v, w), c in bi.items():
        if c >= args.min_bi:
            succ[v].append(w)
    bigram_rows = []
    for v in sorted(succ, key=lambda x: (x != BOS, x)):  # sıralı: çıktı her koşuda aynı bayt
        ws = sorted(succ[v], key=lambda w: (-p_bi_full(v, w), w))[: args.max_succ]
        gamma = D * ctx_types[v] / ctx_count[v]
        bigram_rows.append((v, gamma, [(w, p_bi_full(v, w)) for w in ws]))

    tctx_count = Counter()
    tctx_types = Counter()
    for (u, v, w), c in tri.items():
        tctx_count[(u, v)] += c
        tctx_types[(u, v)] += 1
    tsucc = defaultdict(list)
    for (u, v, w), c in tri.items():
        if c >= args.min_tri:
            tsucc[(u, v)].append((w, c))
    trigram_rows = []
    for (u, v), lst in sorted(tsucc.items()):
        cuv = tctx_count[(u, v)]
        gamma = D * tctx_types[(u, v)] / cuv
        ents = [(w, max(c - D, 0) / cuv + gamma * p_bi_full(v, w)) for w, c in lst]
        ents.sort(key=lambda e: (-e[1], e[0]))
        trigram_rows.append((u, v, gamma, ents[: args.max_succ]))

    deny_exact: set[str] = set()
    deny_prefix: list[str] = []
    if args.denylist:
        for line in args.denylist.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            w = tr_lower(line.lstrip("@"))  # @: yalnız dil modeli; burada hepsi geçerli
            if w.endswith("*"):
                deny_prefix.append(w[:-1])
            else:
                deny_exact.add(w)

    def denied(w):
        base = w.split("'", 1)[0]
        return w in deny_exact or base in deny_exact or any(w.startswith(p) for p in deny_prefix)

    blocked = {w for w in vocab if denied(w)}
    words = sorted(vocab)
    # Motor kelimeyi ikili aramayla bulur (Kotlin UTF-16 sırası): Python kod noktası sırası aynı olmalı.
    assert words == sorted(words, key=lambda w: w.encode("utf-16-be")), "kelime sırası UTF-16 sırasıyla uyuşmuyor"
    words.insert(0, BOS)
    p_uni[BOS] = 1e-9
    ids = {w: i for i, w in enumerate(words)}

    def disp(w):
        if w == BOS:
            return w
        sc = surface.get(w)
        return sc.most_common(1)[0][0] if sc else w

    lg = lambda p: math.log10(max(p, 1e-12))

    def q(p):  # log10 olasılık → 0..255 (0 = 1, 255 = 1e-8; adım ~0,031)
        return min(255, max(0, round(-lg(p) / QMAX * 255)))

    out = bytearray(b"KBNG") + struct.pack(">I", 3)

    def utf(s):
        b = s.encode("utf-8")
        return varint(len(b)) + b

    out += varint(len(words))
    for w in words:
        d = disp(w)
        flags = (1 if w in blocked else 0) | (2 if d != w else 0) | (4 if w != BOS and not surface.get(w) else 0)
        out += utf(w) + bytes([flags, q(p_uni[w])])
        if d != w:
            out += utf(d)
    out += varint(ids[BOS])
    top = sorted(vocab - blocked, key=lambda w: (-p_uni[w], w))[: args.top_uni]
    out += varint(len(top)) + b"".join(varint(ids[w]) for w in top)

    def entries(ents):
        b = bytearray(varint(len(ents)))
        for w, p in ents:
            b += varint(ids[w]) + bytes([q(p)])
        return b

    out += varint(len(bigram_rows))
    prev = 0
    for v, gamma, ents in sorted(bigram_rows, key=lambda r: ids[r[0]]):
        out += varint(ids[v] - prev) + bytes([q(gamma)]) + entries(ents)
        prev = ids[v]
    out += varint(len(trigram_rows))
    for u, v, gamma, ents in sorted(trigram_rows, key=lambda r: (ids[r[0]], ids[r[1]])):
        out += varint(ids[u]) + varint(ids[v]) + bytes([q(gamma)]) + entries(ents)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(bytes(out))
    print(f"cümle: {dict(kept)} (sızıntı nedeniyle atılan: {dict(dropped)}); kelime türü {V}, "
          f"bigram bağlamı {len(bigram_rows)} ({sum(len(r[2]) for r in bigram_rows)} kayıt), "
          f"trigram bağlamı {len(trigram_rows)}; önerisi engellenen {len(blocked)}; {len(out)} bayt → {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
