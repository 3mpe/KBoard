"""Komut satırı: python -m kboard_eval {validate,simulate,score} ..."""

from __future__ import annotations

import argparse
import json
import shlex
import sys
from collections import Counter
from pathlib import Path

from . import metrics, testset, trace
from .predictors import FrequencyPredictor, SubprocessPredictor
from .simulate import simulate


def _cmd_validate(args) -> int:
    cases, errors = testset.load_with_errors(args.testset)
    for e in errors:
        print(f"HATA {e}", file=sys.stderr)
    cats = Counter(c.category for c in cases)
    tags = Counter(t for c in cases for t in c.tags)
    words = sum(len(c.target_words) for c in cases)
    print(f"{len(cases)} geçerli vaka, {words} kelime, {len(errors)} hata")
    print("kategoriler: " + ", ".join(f"{k}={v}" for k, v in sorted(cats.items())))
    if tags:
        print("etiketler:   " + ", ".join(f"{k}={v}" for k, v in sorted(tags.items())))
    return 1 if errors else 0


def _make_predictor(args):
    if args.predictor == "freq":
        if not args.wordlist:
            raise SystemExit("--predictor freq için --wordlist gerekli")
        return FrequencyPredictor.from_file(args.wordlist)
    if args.predictor == "subprocess":
        if not args.command:
            raise SystemExit("--predictor subprocess için --command gerekli")
        return SubprocessPredictor(shlex.split(args.command))
    raise SystemExit(f"bilinmeyen öngörücü: {args.predictor}")


def _cmd_simulate(args) -> int:
    cases = testset.load(args.testset)
    predictor = _make_predictor(args)
    try:
        trace.write(simulate(cases, predictor), args.output)
    finally:
        if hasattr(predictor, "close"):
            predictor.close()
    print(f"iz yazıldı: {args.output}")
    return 0


def _cmd_score(args) -> int:
    cases = testset.load(args.testset)
    events = trace.read(args.trace)
    result = metrics.score(cases, events)
    if args.json:
        Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = metrics.to_markdown(result, args.title)
    if args.markdown:
        Path(args.markdown).write_text(md, encoding="utf-8")
    print(md)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="kboard-eval", description="KBoard Türkçe öneri ölçüm aracı")
    sub = p.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("validate", help="test setini doğrula")
    v.add_argument("testset", nargs="+", type=Path)
    v.set_defaults(func=_cmd_validate)

    s = sub.add_parser("simulate", help="masaüstü öngörücüyle iz üret")
    s.add_argument("testset", nargs="+", type=Path)
    s.add_argument("-o", "--output", type=Path, required=True)
    s.add_argument("--predictor", choices=["freq", "subprocess"], default="freq")
    s.add_argument("--wordlist", type=Path, help="freq: kelime[<TAB>frekans] listesi")
    s.add_argument("--command", help="subprocess: çalıştırılacak motor komutu")
    s.set_defaults(func=_cmd_simulate)

    r = sub.add_parser("score", help="izi puanla")
    r.add_argument("testset", nargs="+", type=Path)
    r.add_argument("-t", "--trace", type=Path, required=True)
    r.add_argument("--json", type=Path)
    r.add_argument("--markdown", type=Path)
    r.add_argument("--title", default="Ölçüm raporu")
    r.set_defaults(func=_cmd_score)

    args = p.parse_args(argv)
    return args.func(args)
