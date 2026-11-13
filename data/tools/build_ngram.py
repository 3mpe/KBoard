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

Biçim (büyük endian, java.io.DataInputStream):
  "KBNG" u32 sürüm(1)
  u32 V; V kez: utf(kelime küçük) utf(gösterim) f32 log10 P_uni
  u32 bos_id                      (cümle başı belirteci "<s>")
  u32 topUniN; topUniN kez u32 id (P_uni'ye göre azalan; bağlam bilinmezken öneri)
  u32 B; B kez: u32 bağlam_id f32 log10 gamma u16 n; n kez: u32 id f32 log10 P   (P'ye göre azalan)
  u32 T; T kez: u32 id1 u32 id2 f32 log10 gamma u16 n; n kez: u32 id f32 log10 P
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
            if not line.strip():
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

    words = sorted(vocab)
    words.insert(0, BOS)
    p_uni[BOS] = 1e-9
    ids = {w: i for i, w in enumerate(words)}

    def disp(w):
        if w == BOS:
            return w
        sc = surface.get(w)
        return sc.most_common(1)[0][0] if sc else w

    lg = lambda p: math.log10(max(p, 1e-12))
    out = bytearray(b"KBNG") + struct.pack(">I", 1)

    def utf(s):
        b = s.encode("utf-8")
        return struct.pack(">H", len(b)) + b

    out += struct.pack(">I", len(words))
    for w in words:
        out += utf(w) + utf(disp(w)) + struct.pack(">f", lg(p_uni[w]))
    out += struct.pack(">I", ids[BOS])
    top = sorted(vocab, key=lambda w: (-p_uni[w], w))[: args.top_uni]
    out += struct.pack(">I", len(top)) + b"".join(struct.pack(">I", ids[w]) for w in top)
    out += struct.pack(">I", len(bigram_rows))
    for v, gamma, ents in bigram_rows:
        out += struct.pack(">IfH", ids[v], lg(gamma), len(ents))
        for w, p in ents:
            out += struct.pack(">If", ids[w], lg(p))
    out += struct.pack(">I", len(trigram_rows))
    for u, v, gamma, ents in trigram_rows:
        out += struct.pack(">IIfH", ids[u], ids[v], lg(gamma), len(ents))
        for w, p in ents:
            out += struct.pack(">If", ids[w], lg(p))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(bytes(out))
    print(f"cümle: {dict(kept)} (sızıntı nedeniyle atılan: {dict(dropped)}); kelime türü {V}, "
          f"bigram bağlamı {len(bigram_rows)} ({sum(len(r[2]) for r in bigram_rows)} kayıt), "
          f"trigram bağlamı {len(trigram_rows)}; {len(out)} bayt → {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
