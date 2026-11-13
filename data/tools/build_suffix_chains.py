#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Ek zinciri tablosunu (data/suffix/chains-tr.tsv) ve kök sınıfı tablosunu (data/suffix/stems-tr.tsv) üretir.

Yalnız masaüstünde, bir kez çalıştırılır; çıktılar depoya girer ve uygulama derlemesi Zemberek'e
bağlı değildir (derleme tabloları `SuffixTool build` ve `DeasciifyTool build` ile ikili dosyaya çevirir).

1. Korpus: dil modeliyle aynı kaynaklar, ağırlıklar ve sızıntı süzgeci (build_ngram.py: test
   setleriyle 4-gram örtüşen cümleler atılır). `--holdout-every N` verilirse her korpusun her N.
   dolu satırı ayrılır (corpus_false_ac.py ile aynı bölme; korpus ölçümü için).
2. Bölütleme: Zemberek (Apache-2.0, tools/zemberek-spike) cümleleri belirsizlik gidermeyle çözümler.
   Her sözcüğün kökü, sınıfı (isim/sıfat → N, özel isim → P, fiil → V) ve biçimbirim dizisi alınır.
3. Soyutlama: biçimbirim dizisi SuffixGenerator şablonlarına çevrilir (ünlü uyumu yer tutucularıyla,
   ör. lAr+(I)m+DA). Çevrilemeyen biçimbirim içeren sözcük sayılmaz (ama sınıf toplamına girer).
4. Doğrulama: `--check` komutu (motorun SuffixTool check'i; tek uyum uygulaması) her sözcüğü kökünden
   ve zincirinden yeniden üretir; korpustaki biçimle uyuşma oranı `--min-agreement`'ın altında kalan
   zincir atılır.
5. Bütçe: en az `--min-count` ağırlıklı sayılı zincirler, sınıf başına en çok `--max-chains`.
6. Kökler: Zemberek kök sözlüğündeki isim/sıfat/özel isim ve fiil kökleri, sözlükte (main_tr.tsv)
   olanlar; fiil kökü sözlükte yoksa mastarı taşıyıcıdır ("anlaşmak"). Öznitelikler: P özel isim,
   Y yumuşama, F ince ek (isim), T ettirgen -t (fiil), I geniş zaman -Ir (fiil), X ünlüyle başlayan
   ek yok (ünlü düşmesi, ikizleşme). Bir sözcüğün birden çok Zemberek kaydı varsa korpusta
   kullanılanı seçilir; isim ile fiil öznitelikleri çelişirse korpusta daha sık olan sınıf kalır.

Kullanım (depo kökünden; Zemberek aracı: `gradle -q -p tools/zemberek-spike installDist`):
  python3 data/tools/build_suffix_chains.py \\
      --zemberek tools/zemberek-spike/build/install/zemberek-spike/bin/zemberek-spike \\
      --check "java -cp 'engine/build/install/engine/lib/*' io.github.kboard.engine.morph.SuffixToolKt check" \\
      --dict engine/build/kboard/main_tr.tsv \\
      --chains-out data/suffix/chains-tr.tsv --stems-out data/suffix/stems-tr.tsv
"""

from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_ngram as b  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CORPORA = [
    ("cv", ROOT / "data/corpus/commonvoice-tr.txt", 1),
    ("sohbet", ROOT / "data/sohbet/sohbet-v1.txt", 3),
]
EXCLUDE = [ROOT / "eval/testset/seed-v0.jsonl", ROOT / "eval/testset/kisisel-v0.jsonl"]

# İsim kökünü tümden dışarıda bırakan Zemberek öznitelikleri (bileşik, örtük çoğul/iyelik...)
# (Örtük çoğul/iyelik kökleri, annemler, akrepler, kalır: -lAr ile başlayan zincir onlara eklenmez.)
NOUN_SKIP = {"CompoundP3sg", "CompoundP3sgRoot", "NoSuffix", "Dummy", "NounConsInsert_n", "NoQuote"}
NOUN_IRREGULAR = {"LastVowelDrop", "Doubling"}
# Fiilde ünlü düşmesi (ayır → ayrıl-) üretilmez: kök tümden dışarıda
VERB_SKIP = {"Dummy", "CompoundP3sgRoot", "LastVowelDrop"}
MIN_STEM = 3

PERSON_PAST = {"A1sg": "m", "A2sg": "n", "A1pl": "k", "A2pl": "nIz", "A3pl": "lAr"}
PERSON_OPT = {"A1sg": "(y)Im", "A2sg": "sIn", "A1pl": "lIm", "A2pl": "sInIz", "A3pl": "lAr"}
PERSON_IMP = {"A3sg": "sIn", "A2pl": "(y)In", "A3pl": "sInlAr"}
PERSON = {"A1sg": "(y)Im", "A2sg": "sIn", "A1pl": "(y)Iz", "A2pl": "sInIz", "A3pl": "lAr"}
POSSESSIVE = {"P1sg": "(I)m", "P2sg": "(I)n", "P3sg": "(s)I", "P1pl": "(I)mIz", "P2pl": "(I)nIz"}
CASES = {"Dat": ("(y)A", "nA"), "Acc": ("(y)I", "nI"), "Loc": ("DA", "nDA"), "Abl": ("DAn", "nDAn"),
         "Gen": ("(n)In", "nIn"), "Ins": ("(y)lA", "(y)lA"), "Equ": ("CA", "ncA")}
NOMINAL_DERIV = {"With": "lI", "Without": "sIz", "Ness": "lIK", "Agt": "CI", "Dim": "CIK", "Ly": "CA",
                 "Related": "sAl", "JustLike": "(I)msI"}
VERB_DERIV = {"Neg": "mA", "Unable": "(y)AmA", "Pass": "P", "Caus": "T",
              "Recip": "(I)ş", "Reflex": "(I)n", "Hastily": "(y)Iver"}
# Fiilden isim/sıfat yapanlar (sonra isim çekimi gelir)
PARTICIPLE = {"Inf1": "mAK", "Inf2": "mA", "Inf3": "(y)Iş", "PastPart": "DIK", "FutPart": "(y)AcAK",
              "PresPart": "(y)An", "NarrPart": "mIş", "FeelLike": "(y)AsI", "NotState": "mAzlIK"}
# Zarf-fiiller (sonrasında ek beklenmez)
CONVERB = {"AfterDoingSo": "(y)Ip", "WithoutHavingDoneSo": "mAdAn", "ByDoingSo": "(y)ArAK",
           "When": "(y)IncA", "SinceDoingSo": "(y)AlI", "AsLongAs": "DIkçA",
           "WithoutBeingAbleToHaveDoneSo": "(y)AmAdAn"}


def chain_templates(ms: list[tuple[str, str]], root_pos: str) -> list[str] | None:
    """Kökten sonraki biçimbirimler → şablonlar; çevrilemiyorsa None. ms: (kimlik, yüzey)."""
    out: list[str] = []
    ctx = "verb" if root_pos == "Verb" else "nom"  # nom | verb | cop (ek-fiil)
    tense = None  # kişi ekinin biçimini belirleyen son kip
    prev = None  # son yüzeyli biçimbirim
    after_neg_aor = False
    zero = False
    for k, (mid, surf) in enumerate(ms):
        if mid in ("Noun", "Adj", "Adv"):
            zero = False
            continue
        if mid == "Zero":
            zero = True
            continue
        if mid == "Verb":
            ctx = "cop" if zero else "verb"
            zero = False
            tense = None
            continue
        if surf == "":
            # Yüzeysiz biçimbirim: yalnız kişi ekinin biçimini belirler
            if mid in ("Imp", "Pres"):
                tense = mid
            elif mid in ("Aor", "AorPart") and prev in ("Neg", "Unable"):
                after_neg_aor = True
                tense = "Aor"
            continue
        t: str | None = None
        if ctx == "nom":
            if mid == "A3pl":
                t = "lAr"
            elif mid in POSSESSIVE:
                t = POSSESSIVE[mid]
            elif mid == "P3pl":
                t = "I" if prev == "A3pl" else "lArI"
            elif mid in CASES:
                t = CASES[mid][1 if prev in ("P3sg", "P3pl", "Rel") else 0]
            elif mid in NOMINAL_DERIV:
                t = NOMINAL_DERIV[mid]
            elif mid == "Rel":
                t = "ki" if surf == "ki" else None
            elif mid == "AsIf":
                t = "CA"
            elif mid in ("Become", "Acquire"):
                t = "lAş" if mid == "Become" else "lAn"
                ctx = "verb"
                tense = None
        elif ctx in ("verb", "cop"):
            cop = ctx == "cop"
            if not cop and mid == "Able":
                # Olumsuzdan önce kısa biçim: de-ye-me-m (Zemberek: Able + Neg)
                t = "(y)Abil" if surf.endswith("bil") else "(y)A"
            elif not cop and mid in VERB_DERIV:
                t = VERB_DERIV[mid]
            elif not cop and mid == "Agt":
                t = "(y)IcI"
                ctx = "nom"
            elif mid == "Past":
                t = "(y)DI" if (cop or tense) else "DI"
            elif mid == "Narr":
                t = "(y)mIş" if (cop or tense) else "mIş"
            elif mid == "Cond":
                t = "(y)sA"
            elif mid == "Cop":
                t = "DIr"
            elif mid == "While":
                t = "(y)ken"
            elif not cop and mid == "Prog1":
                t = "^Iyor"
            elif not cop and mid == "Prog2":
                t = "mAktA"
            elif not cop and mid == "Fut":
                t = "(y)AcAK"
            elif not cop and mid in ("Aor", "AorPart"):
                if prev in ("Neg", "Unable"):
                    t = "z" if surf == "z" else None
                else:
                    t = "R"
                if mid == "AorPart":
                    ctx = "nom"
            elif not cop and mid == "Desr":
                t = "sA"
            elif not cop and mid == "Opt":
                t = "(y)A"
            elif not cop and mid == "Neces":
                t = "mAlI"
            elif mid in PERSON:
                if after_neg_aor and mid in ("A1sg", "A1pl"):
                    t = "m" if mid == "A1sg" else "(y)Iz"
                elif tense in ("Past", "Cond", "Desr"):
                    t = PERSON_PAST[mid]
                elif tense == "Opt":
                    t = PERSON_OPT[mid]
                elif tense == "Imp":
                    t = PERSON_IMP.get(mid)
                    if mid == "A2pl" and surf.endswith(("nız", "niz", "nuz", "nüz")):
                        t = "(y)InIz"
                else:
                    t = PERSON[mid]
            elif mid == "A3sg" and tense == "Imp":
                t = PERSON_IMP["A3sg"]
            elif not cop and mid in PARTICIPLE:
                t = PARTICIPLE[mid]
                ctx = "nom"
            elif not cop and mid in CONVERB:
                t = CONVERB[mid]
                if mid == "WithoutHavingDoneSo" and not surf.startswith("ma") and not surf.startswith("me"):
                    t = None
                ctx = "adv"
            elif mid == "AsIf":
                t = "CAsInA"
                ctx = "adv"
            if mid in ("Past", "Narr", "Cond", "Prog1", "Prog2", "Fut", "Aor", "Desr", "Opt", "Neces", "Cop"):
                tense = mid if mid != "Cop" else tense
        if t is None:
            return None
        if mid not in ("Aor",):
            after_neg_aor = after_neg_aor and mid in PERSON
        out.append(t)
        prev = mid
    return out


_CIRCUMFLEX = str.maketrans("âîûÂÎÛ", "aiuAIU")


def plain(s: str) -> str:
    """Karşılaştırma biçimi: küçük harf, düzeltme işaretsiz (dükkân = dükkan)."""
    return b.tr_lower(s).translate(_CIRCUMFLEX)


def load_sentences(holdout_every: int | None) -> tuple[list[str], list[int]]:
    """Dil modeliyle aynı korpus ve sızıntı süzgeci; satırlar ve ağırlıkları."""
    grams, short = b.load_exclusions(EXCLUDE)
    sents: list[str] = []
    weights: list[int] = []
    for _, path, weight in CORPORA:
        n = 0
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            n += 1
            if holdout_every and n % holdout_every == 0:
                continue
            raw = b.tokens(line)
            if not raw or b.leaks([b.tr_lower(t) for t in raw], grams, short):
                continue
            sents.append(" ".join(line.split()))
            weights.append(weight)
    return sents, weights


def run(cmd: str, mode: str | None, stdin: str) -> list[str]:
    args = shlex.split(cmd) + ([mode] if mode else [])
    p = subprocess.run(args, input=stdin, capture_output=True, text=True, encoding="utf-8")
    if p.returncode != 0:
        sys.stderr.write(p.stderr)
        raise SystemExit(f"komut başarısız: {' '.join(args)}")
    return p.stdout.splitlines()


def noun_attrs(attrs: set[str], proper: bool = False) -> str | None:
    """Zemberek öznitelikleri → isim öznitelik harfleri; isim kökü olamıyorsa None."""
    if attrs & NOUN_SKIP:
        return None
    a = "P" if proper else ""
    if "Voicing" in attrs:
        a += "Y"
    if "InverseHarmony" in attrs:
        a += "F"
    if attrs & NOUN_IRREGULAR:
        a += "X"
    return a


def verb_attrs(attrs: set[str]) -> str | None:
    if attrs & VERB_SKIP:
        return None
    a = ""
    if "Voicing" in attrs:
        a += "Y"
    if "Causative_t" in attrs:
        a += "T"
    if "Aorist_I" in attrs:
        a += "I"
    return a


FLAG_BITS = {"P": 0x40, "Y": 0x10, "F": 0x20, "T": 0x20, "I": 0x40, "X": 0x80}


def flags(cls: str, attrs: str) -> int:
    f = 0x08 if cls == "V" else 0x04
    for c in attrs:
        f |= FLAG_BITS[c]
    return f


def merge_attrs(values: list[str], cls: str, freq: Counter) -> str | None:
    """Aynı sözcüğün birden çok Zemberek kaydı. Korpusta hangisi kullanıldıysa o (en sık); kanıt
    yoksa isimde: cins isim kaydı özel ismin önünde, çelişen yumuşama → X; başka çelişki → None."""
    if len(set(values)) == 1:
        return values[0]
    seen = [(freq[v], v) for v in set(values) if freq[v] > 0]
    if seen:
        return max(seen, key=lambda x: (x[0], x[1]))[1]
    if cls == "N":
        common = [v for v in values if "P" not in v]
        if common:
            values = common
        if len(set(values)) == 1:
            return values[0]
        fs = {"F" in v for v in values}
        if len(fs) > 1:
            return None
        out = "".join(c for c in "PYF" if all(c in v for v in values))
        if any("X" in v for v in values) or len({"Y" in v for v in values}) > 1:
            out += "X"
        return out
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--zemberek", required=True, help="Zemberek aracı komutu (kip argümanı eklenir)")
    ap.add_argument("--check", required=True, help="SuffixTool check komutu")
    ap.add_argument("--dict", required=True, type=Path, help="main_tr.tsv (dict_dump.py)")
    ap.add_argument("--holdout-every", type=int, help="her N. satırı dışarıda bırak (korpus ölçümü)")
    ap.add_argument("--min-count", type=int, default=2)
    ap.add_argument("--min-agreement", type=float, default=0.9)
    ap.add_argument("--max-chains", type=int, default=1500, help="sınıf başına en çok zincir")
    ap.add_argument("--chains-out", required=True, type=Path)
    ap.add_argument("--stems-out", type=Path)
    ap.add_argument("--report", type=Path, help="ayrıntılı uyuşmazlık listesi")
    args = ap.parse_args()

    sents, weights = load_sentences(args.holdout_every)
    print(f"korpus: {len(sents)} cümle", file=sys.stderr)

    # Kök sözlüğü: (lemma, kök, tür, alt tür, öznitelikler)
    lexicon = [line.split("\t") for line in run(args.zemberek, "lexicon", "")]

    total = Counter()  # sınıf → ağırlıklı sözcük sayısı (eksiz dahil)
    chains: dict[tuple[str, str], int] = Counter()
    root_freq = Counter()  # (küçük harf kök, N|V) → ağırlıklı sayı
    attr_freq: dict[tuple[str, str], Counter] = defaultdict(Counter)  # hangi Zemberek kaydı kullanıldı
    samples: dict[tuple[str, str], list[tuple[str, int, str, str]]] = defaultdict(list)  # doğrulama
    untranslated = Counter()
    for line in run(args.zemberek, "analyze", "\n".join(sents) + "\n"):
        f = line.split("\t")
        if len(f) < 8 or f[2] == "-":
            continue
        sid, surface, lemma, root, pos, spos, attrs, morph = f[:8]
        if pos not in ("Noun", "Adj", "Verb") or spos == "Abbrv":
            continue
        w = weights[int(sid)]
        attr = set() if attrs == "-" else set(attrs.split(","))
        if pos == "Verb":
            cls, a, stem = "V", verb_attrs(attr), root
        else:
            cls, a, stem = ("P" if spos == "Prop" else "N"), noun_attrs(attr, spos == "Prop"), lemma
        # "i" ek-fiildir (ise, idi), fiil kökü sayılmaz
        if a is None or "'" in stem or (cls == "V" and stem == "i"):
            continue
        # Özel isim çekimi kesmeyle yazılır; kesmesiz küçük harfli "özel isim" çoğu kez yanlış
        # çözümlemedir (birde → Bird+e)
        if cls == "P" and not ("'" in surface or plain(surface) == plain(stem)):
            continue
        total[cls] += w
        ms = [tuple(m.split(":", 1)) for m in morph.split(" ")]
        root_freq[(b.tr_lower(stem), "V" if cls == "V" else "N")] += w
        attr_freq[(b.tr_lower(stem), "V" if cls == "V" else "N")][a] += w
        tpl = chain_templates(ms[1:], pos)
        if tpl is None:
            untranslated[(cls, " ".join(m[0] for m in ms[1:]))] += w
            continue
        key = (cls, "+".join(tpl) if tpl else "-")
        chains[key] += w
        if tpl and len(samples[key]) < 400:
            # Doğrulama: korpustaki biçim (küçük harf, tek kesme)
            samples[key].append((stem, flags(cls, a), cls, surface))

    # Doğrulama: zinciri SuffixGenerator ile yeniden üret
    keys = [k for k in sorted(samples) if chains[k] >= args.min_count]
    req, idx = [], []
    for k in keys:
        for s in samples[k]:
            req.append(f"{s[0]}\t{s[1]}\t{s[2]}\t{k[1]}")
            idx.append((k, s))
    got = run(args.check, None, "\n".join(req) + "\n")
    assert len(got) == len(idx)
    agree = defaultdict(lambda: [0, 0])
    bad = defaultdict(list)
    for (k, s), g in zip(idx, got):
        # Ünlüyle başlayan ek alamayan kökün (X) üretilmemesi beklenen sonuçtur, sayılmaz
        if g == "-" and s[1] & 0x80:
            continue
        # Cins isim ve fiilde korpustaki kesme yazım hatasıdır (akademi'ye): yok sayılır
        exp, gen = plain(s[3]), plain(g)
        if s[2] != "P":
            exp, gen = exp.replace("'", ""), gen.replace("'", "")
        ok = g != "-" and gen == exp
        agree[k][0] += ok
        agree[k][1] += 1
        if not ok:
            bad[k].append(f"{s[0]}→{g} (korpus {s[3]})")

    kept: dict[str, list[tuple[str, int]]] = defaultdict(list)
    dropped = Counter()
    for (cls, chain), c in chains.items():
        if chain == "-":
            continue
        a = agree.get((cls, chain))
        if c < args.min_count:
            dropped["az"] += c
            continue
        if a and a[1] and a[0] / a[1] < args.min_agreement:
            dropped["uyuşmaz"] += c
            continue
        kept[cls].append((chain, c))
    lines = [
        "# KBoard ek zinciri tablosu (CC0-1.0). Üreten: data/tools/build_suffix_chains.py",
        "# Kaynak: data/corpus/commonvoice-tr.txt (ağırlık 1) + data/sohbet/sohbet-v1.txt (ağırlık 3),",
        "# test setleriyle 4-gram örtüşen cümleler atıldı; bölütleme Zemberek-NLP 0.17.1.",
        "# Sütunlar: sınıf (N isim/sıfat, P özel isim, V fiil) <TAB> zincir (SuffixGenerator şablonları,",
        "# '+' ile; '-' eksiz kullanım, yalnız toplama girer) <TAB> ağırlıklı sözcük sayısı.",
    ]
    if args.holdout_every:
        lines.append(f"# Korpus ölçümü sürümü: her {args.holdout_every}. satır dışarıda (corpus_false_ac.py bölmesi).")
    for cls in sorted(kept):
        lst = sorted(kept[cls], key=lambda x: (-x[1], x[0]))[: args.max_chains]
        # Eksiz kullanım + atılanlar toplamda kalır: olasılık sınıfın bütün sözcüklerine göre
        rest = total[cls] - sum(c for _, c in lst)
        lines.append(f"{cls}\t-\t{rest}")
        lines += [f"{cls}\t{ch}\t{c}" for ch, c in lst]
    args.chains_out.parent.mkdir(parents=True, exist_ok=True)
    args.chains_out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    n_ok = sum(a[0] for a in agree.values())
    n_all = sum(a[1] for a in agree.values())
    print(f"zincir: {', '.join(f'{c} {len(kept[c])}' for c in sorted(kept))}; çevrilemeyen "
          f"{sum(untranslated.values())} sözcük; atılan {dict(dropped)}; üreteç uyuşması {n_ok}/{n_all} "
          f"= %{100 * n_ok / max(1, n_all):.1f} → {args.chains_out}", file=sys.stderr)
    if args.report:
        with args.report.open("w", encoding="utf-8") as fh:
            for k in sorted(bad, key=lambda k: -chains[k]):
                a = agree[k]
                fh.write(f"{k[0]} {k[1]} sayı={chains[k]} uyuşma={a[0]}/{a[1]}: {'; '.join(bad[k][:8])}\n")
            fh.write("\n# çevrilemeyen\n")
            for (cls, m), c in untranslated.most_common(80):
                fh.write(f"{cls} {m} {c}\n")

    if args.stems_out:
        write_stems(args, lexicon, root_freq, attr_freq)
    return 0


def write_stems(args, lexicon, root_freq, attr_freq) -> None:
    dict_words: dict[str, str] = {}
    offensive: set[str] = set()
    for line in args.dict.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        lw = b.tr_lower(cols[0])
        dict_words.setdefault(lw, cols[0])
        if len(cols) > 2 and cols[2] == "1":
            offensive.add(lw)
    nouns: dict[str, list[str]] = defaultdict(list)
    verbs: dict[str, list[str]] = defaultdict(list)
    for lemma, root, pos, spos, attrs in lexicon:
        attr = set() if attrs == "-" else set(attrs.split(","))
        if pos in ("Noun", "Adj") and spos in ("None", "Time", "Prop"):
            key = b.tr_lower(lemma)
            a = noun_attrs(attr, spos == "Prop")
            if a is not None and key in dict_words and len(key) >= MIN_STEM and key.isalpha():
                nouns[key].append(a)
        elif pos == "Verb" and spos == "None":
            key = b.tr_lower(root)
            a = verb_attrs(attr)
            if a is None or len(key) < MIN_STEM or not key.isalpha() or key.endswith(("mak", "mek")):
                continue
            if key in dict_words:
                verbs[key].append(a)
            elif b.tr_lower(lemma) in dict_words:  # taşıyıcı: mastar
                verbs[b.tr_lower(lemma)].append(a)
    rows: dict[str, tuple[str, str]] = {}
    conflicts = 0
    for key in sorted(set(nouns) | set(verbs)):
        if key in offensive:
            continue
        vkey = key[:-3] if key.endswith(("mak", "mek")) else key
        n = merge_attrs(nouns[key], "N", attr_freq[(key, "N")]) if key in nouns else None
        v = merge_attrs(verbs[key], "V", attr_freq[(vkey, "V")]) if key in verbs else None
        if n is not None and v is not None:
            # Ortak bitler: Y ve F/T aynı olmalı; P/I ve X ortak bitte ya da yalnız isimde.
            # Çelişkide korpusta sık olan sınıf kalır.
            if ("Y" in n) != ("Y" in v) or ("F" in n) != ("T" in v) or "X" in n or "P" in n:
                conflicts += 1
                if root_freq[(vkey, "V")] >= root_freq[(key, "N")]:
                    n = None
                else:
                    v = None
        if n is None and v is None:
            continue
        cls = ("N" if n is not None else "") + ("V" if v is not None else "")
        attrs = "".join(sorted(set((n or "") + (v or "")), key="PYFTIX".index)) or "-"
        rows[key] = (cls, attrs)
    out = [
        "# KBoard kök sınıfı tablosu (CC0-1.0). Üreten: data/tools/build_suffix_chains.py",
        "# Zemberek-NLP 0.17.1 kök sözlüğünün main_tr.dict'te olan isim/sıfat/özel isim ve fiil kökleri.",
        "# Sütunlar: sözcük (küçük harf; fiil kökü sözlükte yoksa mastarı) <TAB> sınıf (N, V, NV) <TAB>",
        "# öznitelikler: Y yumuşama, F ince ek (isim), T ettirgen -t (fiil), I geniş zaman -Ir (fiil),",
        "# X ünlüyle başlayan ek yok (isim); '-' hiçbiri. DeasciifyTool bunları dizin bayraklarına yazar.",
    ]
    out += [f"{k}\t{c}\t{a}" for k, (c, a) in sorted(rows.items())]
    args.stems_out.parent.mkdir(parents=True, exist_ok=True)
    args.stems_out.write_text("\n".join(out) + "\n", encoding="utf-8")
    cnt = Counter(c for c, _ in rows.values())
    print(f"kök: {len(rows)} ({dict(sorted(cnt.items()))}), sınıf çelişkisi {conflicts} → {args.stems_out}",
          file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
